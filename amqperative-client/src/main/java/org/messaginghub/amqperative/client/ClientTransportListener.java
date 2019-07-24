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
package org.messaginghub.amqperative.client;

import java.util.concurrent.ScheduledExecutorService;

import org.apache.qpid.proton4j.engine.exceptions.EngineStateException;
import org.apache.qpid.proton4j.engine.impl.ProtonEngine;
import org.messaginghub.amqperative.client.exceptions.ClientExceptionSupport;
import org.messaginghub.amqperative.client.exceptions.ClientFailedException;
import org.messaginghub.amqperative.transport.TransportListener;
import org.messaginghub.amqperative.transport.impl.ByteBufWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;

/**
 * Listens for events from a connection linked Transport and informs the connection
 * of the events.
 */
public class ClientTransportListener implements TransportListener {

    private static final Logger LOG = LoggerFactory.getLogger(ClientTransportListener.class);

    private final ClientConnection connection;
    private final ProtonEngine engine;
    private final ScheduledExecutorService serializer;

    public ClientTransportListener(ClientConnection connection) {
        this.connection = connection;
        this.engine = connection.getEngine();
        this.serializer = connection.getScheduler();
    }

    @Override
    public void onData(ByteBuf incoming) {
        // TODO - if this buffer is pooled than we need to copy it, or we need to do
        //        a copy in our frame decoder vs just using a slice to hold onto the
        //        body.
        ByteBufWrapper bufferAdapter = new ByteBufWrapper(incoming);

        try {
            do {
                engine.ingest(bufferAdapter);
            } while (bufferAdapter.isReadable() && engine.isWritable());
            // TODO - How do we handle case of not all data read ?

            // TODO - properly handle by reading all data from the buffer
            //        currently the wrapper has its own read index.
        } catch (EngineStateException e) {
            LOG.warn("Caught problem during incoming data processing: {}", e.getMessage(), e);
            connection.handleClientException(ClientExceptionSupport.createOrPassthroughFatal(e));
        }
    }

    @Override
    public void onTransportClosed() {
        if (!serializer.isShutdown()) {
            serializer.execute(() -> {
                LOG.debug("Transport connection remotely closed");
                if (!connection.isClosed()) {
                    // We can't send any more output, so close the transport
                    engine.shutdown();
                    connection.handleClientException(new ClientFailedException("Transport connection remotely closed."));
                }
            });
        }
    }

    @Override
    public void onTransportError(Throwable error) {
        if (!serializer.isShutdown()) {
            serializer.execute(() -> {
                LOG.info("Transport failed: {}", error.getMessage());
                if (!connection.isClosed()) {
                    // We can't send any more output, so close the transport
                    engine.shutdown();
                    connection.handleClientException(ClientExceptionSupport.createOrPassthroughFatal(error));
                }
            });
        }
    }
}