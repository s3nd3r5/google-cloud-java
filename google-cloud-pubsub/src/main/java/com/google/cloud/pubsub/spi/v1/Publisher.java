/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.pubsub.spi.v1;

import com.google.api.gax.bundling.FlowController;
import com.google.api.gax.core.RetrySettings;
import com.google.api.gax.grpc.BundlingSettings;
import com.google.auth.Credentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.pubsub.v1.PubsubMessage;
import io.grpc.ManagedChannelBuilder;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import org.joda.time.Duration;

/**
 * A Cloud Pub/Sub <a href="https://cloud.google.com/pubsub/docs/publisher">publisher</a>, that is
 * associated with a specific topic at creation.
 *
 * <p>A {@link Publisher} provides built-in capabilities to automatically handle bundling of
 * messages, controlling memory utilization, and retrying API calls on transient errors.
 *
 * <p>With customizable options that control:
 *
 * <ul>
 *   <li>Message bundling: such as number of messages or max bundle byte size.
 *   <li>Flow control: such as max outstanding messages and maximum outstanding bytes.
 *   <li>Retries: such as the maximum duration of retries for a failing bundle of messages.
 * </ul>
 *
 * <p>If no credentials are provided, the {@link Publisher} will use application default credentials
 * through {@link GoogleCredentials#getApplicationDefault}.
 *
 * <p>For example, a {@link Publisher} can be constructed and used to publish a list of messages as
 * follows:
 *
 * <pre><code>
 *  Publisher publisher =
 *       Publisher.Builder.newBuilder(MY_TOPIC)
 *           .setMaxBundleDuration(new Duration(10 * 1000))
 *           .build();
 *  List&lt;ListenableFuture&lt;String&gt;&gt; results = new ArrayList&lt;&gt;();
 *
 *  for (PubsubMessage messages : messagesToPublish) {
 *    results.add(publisher.publish(message));
 *  }
 *
 *  Futures.addCallback(
 *  Futures.allAsList(results),
 *  new FutureCallback&lt;List&lt;String&gt;&gt;() {
 *    &#64;Override
 *    public void onSuccess(List&lt;String&gt; messageIds) {
 *      // ... process the acknowledgement of publish ...
 *    }
 *    &#64;Override
 *    public void onFailure(Throwable t) {
 *      // .. handle the failure ...
 *    }
 *  });
 *
 *  // Ensure all the outstanding messages have been published before shutting down your process.
 *  publisher.shutdown();
 * </code></pre>
 */
public interface Publisher {
  // API limits.
  int MAX_BUNDLE_MESSAGES = 1000;
  int MAX_BUNDLE_BYTES = 10 * 1000 * 1000; // 10 megabytes (https://en.wikipedia.org/wiki/Megabyte)

  /** Topic to which the publisher publishes to. */
  String getTopic();

  /**
   * Schedules the publishing of a message. The publishing of the message may occur immediately or
   * be delayed based on the publisher bundling options.
   *
   * <p>Depending on chosen flow control {@link #failOnFlowControlLimits option}, the returned
   * future might immediately fail with a {@link FlowController.FlowControlException} or block the
   * current thread until there are more resources available to publish.
   *
   * @param message the message to publish.
   * @return the message ID wrapped in a future.
   */
  ListenableFuture<String> publish(PubsubMessage message);

  /** Maximum amount of time to wait until scheduling the publishing of messages. */
  Duration getMaxBundleDuration();

  /** Maximum number of bytes to bundle before publishing. */
  long getMaxBundleBytes();

  /** Maximum number of messages to bundle before publishing. */
  long getMaxBundleMessages();

  /**
   * Maximum number of outstanding (i.e. pending to publish) messages before limits are enforced.
   * See {@link #failOnFlowControlLimits()}.
   */
  Optional<Integer> getMaxOutstandingElementCount();

  /**
   * Maximum number of outstanding (i.e. pending to publish) bytes before limits are enforced. See
   * {@link #failOnFlowControlLimits()}.
   */
  Optional<Integer> getMaxOutstandingRequestBytes();

  /**
   * Whether to block publish calls when reaching flow control limits (see {@link
   * #getMaxOutstandingRequestBytes()} and {@link #getMaxOutstandingElementCount()}).
   *
   * <p>If set to false, a publish call will fail with either {@link
   * FlowController.MaxOutstandingRequestBytesReachedException} or {@link
   * FlowController.MaxOutstandingElementCountReachedException}, as appropriate, when flow control
   * limits are reached.
   */
  boolean failOnFlowControlLimits();

  /** Retrieves a snapshot of the publisher current {@link PublisherStats statistics}. */
  PublisherStats getStats();

  /**
   * Schedules immediate publishing of any outstanding messages and waits until all are processed.
   *
   * <p>Sends remaining outstanding messages and prevents future calls to publish. This method
   * should be invoked prior to deleting the {@link Publisher} object in order to ensure that no
   * pending messages are lost.
   */
  void shutdown();

  /** A builder of {@link Publisher}s. */
  public final class Builder {
    static final Duration MIN_TOTAL_TIMEOUT = new Duration(10 * 1000); // 10 seconds
    static final Duration MIN_RPC_TIMEOUT = new Duration(10); // 10 milliseconds

    // Meaningful defaults.
    static final long DEFAULT_ELEMENT_COUNT_THRESHOLD = 100L;
    static final long DEFAULT_REQUEST_BYTES_THRESHOLD = 1000L; // 1 kB
    static final Duration DEFAULT_DELAY_THRESHOLD = new Duration(1); // 1ms
    static final Duration DEFAULT_RPC_TIMEOUT = new Duration(10 * 1000); // 10 seconds
    static final Duration DEFAULT_TOTAL_TIMEOUT = MIN_TOTAL_TIMEOUT;
    static final BundlingSettings DEFAULT_BUNDLING_SETTINGS =
        BundlingSettings.newBuilder()
            .setDelayThreshold(DEFAULT_DELAY_THRESHOLD)
            .setRequestByteThreshold(DEFAULT_REQUEST_BYTES_THRESHOLD)
            .setElementCountThreshold(DEFAULT_ELEMENT_COUNT_THRESHOLD)
            .build();
    static final RetrySettings DEFAULT_RETRY_SETTINGS =
        RetrySettings.newBuilder()
            .setTotalTimeout(DEFAULT_TOTAL_TIMEOUT)
            .setInitialRetryDelay(Duration.millis(5))
            .setRetryDelayMultiplier(2)
            .setMaxRetryDelay(Duration.millis(Long.MAX_VALUE))
            .setInitialRpcTimeout(DEFAULT_RPC_TIMEOUT)
            .setRpcTimeoutMultiplier(2)
            .setMaxRpcTimeout(DEFAULT_RPC_TIMEOUT)
            .build();

    String topic;

    // Bundling options
    BundlingSettings bundlingSettings = DEFAULT_BUNDLING_SETTINGS;

    // Client-side flow control options
    FlowController.Settings flowControlSettings = FlowController.Settings.DEFAULT;
    boolean failOnFlowControlLimits = false;

    RetrySettings retrySettings = DEFAULT_RETRY_SETTINGS;

    // Channels and credentials
    Optional<Credentials> userCredentials = Optional.absent();
    Optional<ManagedChannelBuilder<? extends ManagedChannelBuilder<?>>> channelBuilder =
        Optional.absent();

    Optional<ScheduledExecutorService> executor = Optional.absent();

    /** Constructs a new {@link Builder} using the given topic. */
    public static Builder newBuilder(String topic) {
      return new Builder(topic);
    }

    Builder(String topic) {
      this.topic = Preconditions.checkNotNull(topic);
    }

    /**
     * Credentials to authenticate with.
     *
     * <p>Must be properly scoped for accessing Cloud Pub/Sub APIs.
     */
    public Builder setCredentials(Credentials userCredentials) {
      this.userCredentials = Optional.of(Preconditions.checkNotNull(userCredentials));
      return this;
    }

    /**
     * ManagedChannelBuilder to use to create Channels.
     *
     * <p>Must point at Cloud Pub/Sub endpoint.
     */
    public Builder setChannelBuilder(
        ManagedChannelBuilder<? extends ManagedChannelBuilder<?>> channelBuilder) {
      this.channelBuilder =
          Optional.<ManagedChannelBuilder<? extends ManagedChannelBuilder<?>>>of(
              Preconditions.checkNotNull(channelBuilder));
      return this;
    }

    // Bundling options
    public Builder setBundlingSettings(BundlingSettings bundlingSettings) {
      Preconditions.checkNotNull(bundlingSettings);
      Preconditions.checkNotNull(bundlingSettings.getElementCountThreshold());
      Preconditions.checkArgument(bundlingSettings.getElementCountThreshold() > 0);
      Preconditions.checkNotNull(bundlingSettings.getRequestByteThreshold());
      Preconditions.checkArgument(bundlingSettings.getRequestByteThreshold() > 0);
      Preconditions.checkNotNull(bundlingSettings.getDelayThreshold());
      Preconditions.checkArgument(bundlingSettings.getDelayThreshold().getMillis() > 0);

      Preconditions.checkArgument(
          bundlingSettings.getElementCountLimit() == null,
          "elementCountLimit option not honored by current implementation");
      Preconditions.checkArgument(
          bundlingSettings.getRequestByteLimit() == null,
          "requestByteLimit option not honored by current implementation");
      Preconditions.checkArgument(
          bundlingSettings.getBlockingCallCountThreshold() == null,
          "blockingCallCountThreshold option not honored by current implementation");

      this.bundlingSettings = bundlingSettings;
      return this;
    }

    // Flow control options

    /** Sets the flow control settings. */
    public Builder setFlowControlSettings(FlowController.Settings flowControlSettings) {
      this.flowControlSettings = Preconditions.checkNotNull(flowControlSettings);
      return this;
    }

    /**
     * Whether to fail publish when reaching any of the flow control limits, with either a {@link
     * FlowController.MaxOutstandingRequestBytesReachedException} or {@link
     * FlowController.MaxOutstandingElementCountReachedException} as appropriate.
     *
     * <p>If set to false, then publish operations will block the current thread until the
     * outstanding requests go under the limits.
     */
    public Builder setFailOnFlowControlLimits(boolean fail) {
      failOnFlowControlLimits = fail;
      return this;
    }

    /** Configures the Publisher's retry parameters. */
    public Builder setRetrySettings(RetrySettings retrySettings) {
      Preconditions.checkArgument(
          retrySettings.getTotalTimeout().compareTo(MIN_TOTAL_TIMEOUT) >= 0);
      Preconditions.checkArgument(
          retrySettings.getInitialRpcTimeout().compareTo(MIN_RPC_TIMEOUT) >= 0);
      this.retrySettings = retrySettings;
      return this;
    }

    /** Gives the ability to set a custom executor to be used by the library. */
    public Builder setExecutor(ScheduledExecutorService executor) {
      this.executor = Optional.of(Preconditions.checkNotNull(executor));
      return this;
    }

    public Publisher build() throws IOException {
      return new PublisherImpl(this);
    }
  }
}