/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.qpid.protonj2.client;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Options that control the reconnection behavior of a client {@link Connection}.
 */
public class ReconnectOptions {

    public static final boolean DEFAULT_RECONNECT_ENABLED = false;

    private boolean reconnectEnabled = DEFAULT_RECONNECT_ENABLED;

    // TODO - Use String host and port values ?
    //        Naming ? fallbackLocation, fallbackHost, reconnectAddress etc?
    private final Set<URI> reconnectHosts = new LinkedHashSet<>();

    /**
     * Create a new {@link ConnectionOptions} instance configured with default configuration settings.
     */
    public ReconnectOptions() {
    }

    /**
     * Creates a {@link ReconnectOptions} instance that is a copy of the given instance.
     *
     * @param options
     *      The {@link ReconnectOptions} instance whose configuration should be copied to this one.
     */
    public ReconnectOptions(ReconnectOptions options) {
        if (options != null) {
            options.copyInto(this);
        }
    }

    /**
     * Copy all options from this {@link ReconnectOptions} instance into the instance
     * provided.
     *
     * @param other
     *      the target of this copy operation.
     *
     * @return this {@link ReconnectOptions} instance.
     */
    protected ReconnectOptions copyInto(ReconnectOptions other) {
        other.reconnectEnabled(reconnectEnabled());

        return this;
    }

    /**
     * Returns <code>true</code> if reconnect is currently enabled for the {@link Connection} that
     * these options are assigned to.
     *
     * @return the reconnect enabled configuration state for this options instance.
     */
    public boolean reconnectEnabled() {
        return reconnectEnabled;
    }

    /**
     * Set to <code>true</code> to enable reconnection support on the associated {@link Connection}
     * or <code>false</code> to disable.  When enabled a {@link Connection} will attempt to reconnect
     * to a remote based on the configuration set in this options instance.
     *
     * @param reconnectEnabled
     *      Controls if reconnection is enabled or not for the associated {@link Connection}.
     *
     * @return this {@link ReconnectOptions} instance.
     */
    public ReconnectOptions reconnectEnabled(boolean reconnectEnabled) {
        this.reconnectEnabled = reconnectEnabled;
        return this;
    }

    /**
     * Adds an additional host location that can be used when attempting to reconnect the client
     * following a connection failure.
     *
     * @param reconnectHost
     *      The {@link URI} of the remote host to attempt a reconnection to.
     *
     * @return this {@link ReconnectOptions} instance.
     */
    public ReconnectOptions addReconnectHost(URI reconnectHost) {
        this.reconnectHosts.add(reconnectHost);
        return this;
    }
}
