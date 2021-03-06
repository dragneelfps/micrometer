/**
 * Copyright 2017 VMware, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.core.instrument.binder.okhttp3;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.lang.NonNullApi;
import io.micrometer.core.lang.NonNullFields;
import io.micrometer.core.lang.Nullable;
import okhttp3.*;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

import static java.util.stream.Collectors.toList;
import static java.util.stream.StreamSupport.stream;

/**
 * {@link EventListener} for collecting metrics from {@link OkHttpClient}.
 * <p>
 * {@literal uri} tag is usually limited to URI patterns to mitigate tag cardinality explosion but {@link OkHttpClient}
 * doesn't provide URI patterns. We provide {@value URI_PATTERN} header to support {@literal uri} tag or you can
 * configure a {@link Builder#uriMapper(Function) URI mapper} to provide your own tag values for {@literal uri} tag.
 *
 * @author Bjarte S. Karlsen
 * @author Jon Schneider
 * @author Nurettin Yilmaz
 */
@NonNullApi
@NonNullFields
public class OkHttpMetricsEventListener extends EventListener {

    /**
     * Header name for URI patterns which will be used for tag values.
     */
    public static final String URI_PATTERN = "URI_PATTERN";

    private static final boolean REQUEST_TAG_CLASS_EXISTS;

    static {
        REQUEST_TAG_CLASS_EXISTS = getMethod("tag", Class.class) != null;
    }

    private static Method getMethod(String name, Class<?>... parameterTypes) {
        try {
            return Request.class.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private final MeterRegistry registry;
    private final String requestsMetricName;
    private final Function<Request, String> urlMapper;
    private final Iterable<Tag> extraTags;
    private final Iterable<BiFunction<Request, Response, Tag>> contextSpecificTags;
    private final boolean includeHostTag;

    // VisibleForTesting
    final ConcurrentMap<Call, CallState> callState = new ConcurrentHashMap<>();

    protected OkHttpMetricsEventListener(MeterRegistry registry, String requestsMetricName, Function<Request, String> urlMapper,
                                         Iterable<Tag> extraTags,
                                         Iterable<BiFunction<Request, Response, Tag>> contextSpecificTags) {
        this(registry, requestsMetricName, urlMapper, extraTags, contextSpecificTags, true);
    }

    OkHttpMetricsEventListener(MeterRegistry registry, String requestsMetricName, Function<Request, String> urlMapper,
                               Iterable<Tag> extraTags,
                               Iterable<BiFunction<Request, Response, Tag>> contextSpecificTags, boolean includeHostTag) {
        this.registry = registry;
        this.requestsMetricName = requestsMetricName;
        this.urlMapper = urlMapper;
        this.extraTags = extraTags;
        this.contextSpecificTags = contextSpecificTags;
        this.includeHostTag = includeHostTag;
    }

    public static Builder builder(MeterRegistry registry, String name) {
        return new Builder(registry, name);
    }

    @Override
    public void callStart(Call call) {
        callState.put(call, new CallState(registry.config().clock().monotonicTime(), call.request()));
    }

    @Override
    public void callFailed(Call call, IOException e) {
        CallState state = callState.remove(call);
        if (state != null) {
            state.exception = e;
            time(state);
        }
    }

    @Override
    public void callEnd(Call call) {
        callState.remove(call);
    }

    @Override
    public void responseHeadersEnd(Call call, Response response) {
        CallState state = callState.remove(call);
        if (state != null) {
            state.response = response;
            time(state);
        }
    }

    // VisibleForTesting
    void time(CallState state) {
        Request request = state.request;
        boolean requestAvailable = request != null;

        Tags requestTags = requestAvailable ? getRequestTags(request).and(generateTagsForRoute(request)) : Tags.empty();

        Iterable<Tag> tags = Tags.of(
                        "method", requestAvailable ? request.method() : "UNKNOWN",
                        "uri", getUriTag(state, request),
                        "status", getStatusMessage(state.response, state.exception)
                )
                .and(extraTags)
                .and(stream(contextSpecificTags.spliterator(), false)
                        .map(contextTag -> contextTag.apply(request, state.response))
                        .collect(toList()))
                .and(requestTags);
        tags = includeHostTag ? Tags.of(tags).and("host", requestAvailable ? request.url().host() : "UNKNOWN") : tags;

        Timer.builder(this.requestsMetricName)
                .tags(tags)
                .description("Timer of OkHttp operation")
                .register(registry)
                .record(registry.config().clock().monotonicTime() - state.startTime, TimeUnit.NANOSECONDS);
    }

    private Tags generateTagsForRoute(Request request) {
        return Tags.of(
                "target.scheme", request.url().scheme(),
                "target.host", request.url().host(),
                "target.port", Integer.toString(request.url().port())
        );
    }

    private String getUriTag(CallState state, @Nullable Request request) {
        if (request == null) {
            return "UNKNOWN";
        }
        return state.response != null && (state.response.code() == 404 || state.response.code() == 301)
                    ? "NOT_FOUND" : urlMapper.apply(request);
    }

    private Tags getRequestTags(Request request) {
        if (REQUEST_TAG_CLASS_EXISTS) {
            Tags requestTag = request.tag(Tags.class);
            if (requestTag != null) {
                return requestTag;
            }
        }
        Object requestTag = request.tag();
        if (requestTag instanceof Tags) {
            return (Tags) requestTag;
        }
        return Tags.empty();
    }

    private String getStatusMessage(@Nullable Response response, @Nullable IOException exception) {
        if (exception != null) {
            return "IO_ERROR";
        }

        if (response == null) {
            return "CLIENT_ERROR";
        }

        return Integer.toString(response.code());
    }

    // VisibleForTesting
    static class CallState {
        final long startTime;
        @Nullable
        final Request request;
        @Nullable
        Response response;
        @Nullable
        IOException exception;

        CallState(long startTime, @Nullable Request request) {
            this.startTime = startTime;
            this.request = request;
        }
    }

    public static class Builder {
        private final MeterRegistry registry;
        private final String name;
        private Function<Request, String> uriMapper = (request) -> Optional.ofNullable(request.header(URI_PATTERN)).orElse("none");
        private Tags tags = Tags.empty();
        private Collection<BiFunction<Request, Response, Tag>> contextSpecificTags = new ArrayList<>();
        private boolean includeHostTag = true;

        Builder(MeterRegistry registry, String name) {
            this.registry = registry;
            this.name = name;
        }

        public Builder tags(Iterable<Tag> tags) {
            this.tags = this.tags.and(tags);
            return this;
        }

        /**
         * Add a {@link Tag} to any already configured tags on this Builder.
         *
         * @param tag tag to add
         * @return this builder
         * @since 1.5.0
         */
        public Builder tag(Tag tag) {
            this.tags = this.tags.and(tag);
            return this;
        }

        /**
         * Add a context-specific tag.
         *
         * @param contextSpecificTag function to create a context-specific tag
         * @return this builder
         * @since 1.5.0
         */
        public Builder tag(BiFunction<Request, Response, Tag> contextSpecificTag) {
            this.contextSpecificTags.add(contextSpecificTag);
            return this;
        }

        public Builder uriMapper(Function<Request, String> uriMapper) {
            this.uriMapper = uriMapper;
            return this;
        }

        /**
         * Historically, OkHttp Metrics provided by {@link OkHttpMetricsEventListener} included a
         * {@code host} tag for the target host being called. To align with other HTTP client metrics,
         * this was changed to {@code target.host}, but to maintain backwards compatibility the {@code host}
         * tag can also be included. By default, {@code includeHostTag} is {@literal true} so both tags are included.
         *
         * @param includeHostTag whether to include the {@code host} tag
         * @return this builder
         * @since 1.5.0
         */
        public Builder includeHostTag(boolean includeHostTag) {
            this.includeHostTag = includeHostTag;
            return this;
        }

        public OkHttpMetricsEventListener build() {
            return new OkHttpMetricsEventListener(registry, name, uriMapper, tags, contextSpecificTags, includeHostTag);
        }
    }
}
