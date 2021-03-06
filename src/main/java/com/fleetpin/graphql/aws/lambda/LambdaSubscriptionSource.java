/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.fleetpin.graphql.aws.lambda;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fleetpin.graphql.aws.lambda.subscription.SubscriptionResponseData;
import com.fleetpin.graphql.database.manager.dynamo.DynamoDbManager;
import com.google.common.annotations.VisibleForTesting;
import graphql.ExecutionResult;
import graphql.GraphQL;
import io.reactivex.rxjava3.core.Flowable;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiAsyncClient;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.PostToConnectionResponse;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public abstract class LambdaSubscriptionSource<E, T> implements RequestHandler<E, Void> {

    private static final Logger logger = LoggerFactory.getLogger(LambdaSubscriptionSource.class);

    private final DynamoDbManager manager;
    private final ApiGatewayManagementApiAsyncClient gatewayApi;
    private final GraphQL graph;


    private final LambdaCache<String, CompletableFuture<GetItemResponse>> userCache;
    private final LambdaCache<String, CompletableFuture<QueryResponse>> organisationCache;
    private final String subscriptionTable;

    private final long sentMessageTimeout;

    public LambdaSubscriptionSource(String subscriptionId, String subscriptionTable, String apiUri, Duration userCacheTTL, Duration subscriptionCacheTTL) throws Exception {
        prepare();

        this.subscriptionTable = subscriptionTable;
        this.manager = builderManager();
        this.graph = buildGraphQL();

        if (apiUri == null) {
            gatewayApi = null;
        } else {
            final var endpoint = new URI(apiUri);
            this.gatewayApi = ApiGatewayManagementApiAsyncClient.builder().endpointOverride(endpoint).build();
        }

        //TODO: make configurable
        organisationCache = new LambdaCache<>(subscriptionCacheTTL, lookupId -> {
            final Map<String, AttributeValue> keyConditions = new HashMap<>();

            keyConditions.put(":subscription", AttributeValue.builder().s(subscriptionId + ":" + lookupId).build());

            return manager
                    .getDynamoDbAsyncClient()
                    .query(t -> t
                            .tableName(subscriptionTable)
                            .indexName("subscription")
                            .keyConditionExpression("subscription = :subscription")
                            .expressionAttributeValues(keyConditions)
                    );
        });

        userCache = new LambdaCache<>(userCacheTTL, connectionId -> {
            final Map<String, AttributeValue> key = new HashMap<>();

            key.put("connectionId", AttributeValue.builder().s(connectionId).build());
            key.put("id", AttributeValue.builder().s("auth").build());

            return manager.getDynamoDbAsyncClient().getItem(t -> t.tableName(subscriptionTable).key(key));
        });

        sentMessageTimeout = Long.parseLong(
                System.getenv(Constants.ENV_SENT_MESSAGE_TIMEOUT) != null ?
                        System.getenv(Constants.ENV_SENT_MESSAGE_TIMEOUT) :
                        Duration.ofMinutes(2).toMillis() + ""
        );
    }

    protected abstract void prepare() throws Exception;

    protected abstract GraphQL buildGraphQL() throws Exception;

    protected abstract DynamoDbManager builderManager();

    public abstract CompletableFuture<ContextGraphQL> buildContext(
            Flowable<T> publisher,
            String userId,
            AttributeValue additionalUserInfo,
            Map<String, Object> variables
    );

    public abstract String buildSubscriptionId(T type);

    @VisibleForTesting
    protected CompletableFuture<?> process(T t) {
        return organisationCache.get(buildSubscriptionId(t)).thenCompose(items -> {
            final List<CompletableFuture<Void>> parts = new ArrayList<>();

            for (var item : items.items()) {
                final var connectionId = item.get("connectionId").s();
                final var id = item.get("id").s();
                final var query = manager.convertTo(item.get("query"), GraphQLQuery.class);

                parts.add(processUpdate(connectionId, id, query, t));
            }

            return CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new));
        });

    }


    private CompletableFuture<Void> processUpdate(String connectionId, String id, GraphQLQuery query, T t) {
        return userCache.get(connectionId).thenCompose(user -> {
            if (user.item() == null || user.item().isEmpty()) {
                //not authenticated
                return CompletableFuture.completedFuture(null);
            }

            final Flowable<T> publisher = Flowable.just(t);

            return buildContext(publisher, user.item().get("user").s(), user.item().get("aditional"), query.getVariables())
                    .thenCompose(context -> {
                        final var toReturn = graph
                                .executeAsync(builder -> builder
                                        .query(query.getQuery())
                                        .operationName(query.getOperationName())
                                        .variables(query.getVariables())
                                        .context(context)
                                );

                        context.start(toReturn);

                        return toReturn.thenCompose(r -> {
                            if (!r.getErrors().isEmpty()) {
                                try {
                                    final var data = new SubscriptionResponseData(id, r);
                                    final var sendResponse = manager.getMapper().writeValueAsString(data);

                                    return sendMessage(connectionId, sendResponse).thenAccept(__ -> {
                                    });
                                } catch (JsonProcessingException e) {
                                    throw new UncheckedIOException(e);
                                }
                            }

                            final Publisher<ExecutionResult> stream = r.getData();
                            final CompletionStage<Void> requestSent = Flowable
                                    .fromPublisher(stream)
                                    .map(item -> {
                                        final var data = new SubscriptionResponseData(id, item);

                                        try {
                                            final var sendResponse = manager.getMapper().writeValueAsString(data);

                                            return sendMessage(connectionId, sendResponse)
                                                    .handle((response, error) -> {
                                                        if (error != null) {
                                                            logger.error("Deleting user", error);

                                                            return deleteUser(user);
                                                        }

                                                        return CompletableFuture.completedFuture(null);
                                                    }).thenCompose(promise -> promise).thenAccept(__ -> {
                                                    });
                                        } catch (JsonProcessingException e) {
                                            throw new UncheckedIOException(e);
                                        }
                                    })
                                    .singleElement()
                                    .toCompletionStage(CompletableFuture.completedFuture(null))
                                    .thenCompose(f -> f).thenAccept(__ -> {
                                    });

                            context.start(requestSent);

                            return requestSent;
                        });
                    });
        });


    }


    private CompletableFuture<Void> deleteUser(GetItemResponse user) {
        final Map<String, AttributeValue> keyConditions = new HashMap<>();

        keyConditions.put(":connectionId", user.item().get("connectionId"));

        return manager
                .getDynamoDbAsyncClient()
                .query(t -> t.tableName(subscriptionTable)
                        .keyConditionExpression("connectionId = :connectionId")
                        .expressionAttributeValues(keyConditions))
                .thenCompose(items -> {
                    final var futures = items
                            .items()
                            .stream()
                            .map(item -> {
                                final var key = new HashMap<>(item);

                                key
                                        .keySet()
                                        .retainAll(Arrays.asList("connectionId", "id"));

                                return manager
                                        .getDynamoDbAsyncClient()
                                        .deleteItem(t -> t.tableName(subscriptionTable).key(key));
                            })
                            .toArray(CompletableFuture[]::new);
                    return CompletableFuture.allOf(futures);
                });


    }

    @VisibleForTesting
    protected CompletableFuture<PostToConnectionResponse> sendMessage(String connectionId, String sendResponse) {
        return gatewayApi
                .postToConnection(b -> b
                        .overrideConfiguration(
                                c -> c
                                        .apiCallTimeout(Duration.ofMillis(sentMessageTimeout))
                                        .apiCallAttemptTimeout(Duration.ofMillis(sentMessageTimeout))
                        )
                        .connectionId(connectionId)
                        .data(SdkBytes.fromString(sendResponse, StandardCharsets.UTF_8))
                );
    }

}
