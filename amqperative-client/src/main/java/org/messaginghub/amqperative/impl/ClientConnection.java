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
package org.messaginghub.amqperative.impl;

import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Supplier;

import org.apache.qpid.proton4j.buffer.ProtonBuffer;
import org.apache.qpid.proton4j.engine.ConnectionState;
import org.apache.qpid.proton4j.engine.Engine;
import org.apache.qpid.proton4j.engine.EngineFactory;
import org.apache.qpid.proton4j.engine.sasl.client.SaslAuthenticator;
import org.apache.qpid.proton4j.engine.sasl.client.SaslCredentialsProvider;
import org.apache.qpid.proton4j.engine.sasl.client.SaslMechanismSelector;
import org.messaginghub.amqperative.Client;
import org.messaginghub.amqperative.Connection;
import org.messaginghub.amqperative.ConnectionOptions;
import org.messaginghub.amqperative.Message;
import org.messaginghub.amqperative.Receiver;
import org.messaginghub.amqperative.ReceiverOptions;
import org.messaginghub.amqperative.Sender;
import org.messaginghub.amqperative.SenderOptions;
import org.messaginghub.amqperative.Session;
import org.messaginghub.amqperative.SessionOptions;
import org.messaginghub.amqperative.Tracker;
import org.messaginghub.amqperative.futures.AsyncResult;
import org.messaginghub.amqperative.futures.ClientFuture;
import org.messaginghub.amqperative.futures.ClientFutureFactory;
import org.messaginghub.amqperative.impl.exceptions.ClientClosedException;
import org.messaginghub.amqperative.impl.exceptions.ClientConnectionRemotelyClosedException;
import org.messaginghub.amqperative.impl.exceptions.ClientExceptionSupport;
import org.messaginghub.amqperative.impl.exceptions.ClientOperationTimedOutException;
import org.messaginghub.amqperative.impl.exceptions.ClientUnsupportedOperationException;
import org.messaginghub.amqperative.transport.Transport;
import org.messaginghub.amqperative.transport.TransportBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link Connection} implementation that uses the Proton engine for AMQP protocol support.
 */
public class ClientConnection implements Connection {

    private static final Logger LOG = LoggerFactory.getLogger(ClientConnection.class);

    private static final long INFINITE = -1;
    private static final AtomicInteger CONNECTION_SEQUENCE = new AtomicInteger();

    // Future tracking of Closing. Closed. Failed state vs just simple boolean is intended here
    // later on we may decide this is overly optimized.
    private static final AtomicIntegerFieldUpdater<ClientConnection> CLOSED_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(ClientConnection.class, "closed");
    private static final AtomicReferenceFieldUpdater<ClientConnection, ClientException> FAILURE_CAUSE_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(ClientConnection.class, ClientException.class, "failureCause");

    private final ClientInstance client;
    private final ConnectionOptions options;
    private final ClientConnectionCapabilities capabilities = new ClientConnectionCapabilities();
    private final ClientFutureFactory futureFactoy;

    private final Map<ClientFuture<?>, ClientFuture<?>> requests = new ConcurrentHashMap<>();
    private final Engine engine;
    private org.apache.qpid.proton4j.engine.Connection protonConnection;
    private ClientSession connectionSession;
    private ClientSender connectionSender;
    private Transport transport;

    // TODO - Ensure closed sessions are removed from this list - Use a Map otherwise there's gaps
    //        We might be able to simplify how this is handled by extending events and or offering
    //        APIs in proton4j to access these already known resources.
    private final List<ClientSession> sessions = new ArrayList<>();

    private SessionOptions defaultSessionOptions;

    private ClientFuture<Connection> openFuture;
    private ClientFuture<Connection> closeFuture;
    private volatile int closed;
    private final AtomicInteger sessionCounter = new AtomicInteger();
    private volatile ClientException failureCause;
    private final String connectionId;

    private ScheduledExecutorService executor;

    /**
     * Create a connection and define the initial configuration used to manage the
     * connection to the remote.
     *
     * @param host
     * 		the host that this connection is connecting to.
     * @param port
     * 		the port on the remote host where this connection attaches.
     * @param client
     *      the {@link Client} that this connection resides within.
     * @param options
     *      the connection options that configure this {@link Connection} instance.
     */
    public ClientConnection(ClientInstance client, String host, int port, ConnectionOptions options) {
        this.client = client;
        this.options = options;
        this.connectionId = client.nextConnectionId();
        this.futureFactoy = ClientFutureFactory.create(options.futureType());

        if (options.saslEnabled()) {
            // TODO - Check that all allowed mechanisms are actually supported ?
            engine = EngineFactory.PROTON.createEngine();
        } else {
            engine = EngineFactory.PROTON.createNonSaslEngine();
        }

        ThreadFactory transportThreadFactory = new ClientThreadFactory(
            "ProtonConnection :(" + CONNECTION_SEQUENCE.incrementAndGet()
                          + "):[" + host + ":" + port + "]", true);

        transport = new TransportBuilder().host(host)
                                          .port(port)
                                          .transportOptions(options.transportOptions())
                                          .sslOptions(options.sslOptions())
                                          .transportListener(new ClientTransportListener(this))
                                          .threadFactory(transportThreadFactory)
                                          .build();

        openFuture = futureFactoy.createFuture();
        closeFuture = futureFactoy.createFuture();
    }

    @Override
    public ClientInstance client() {
        return client;
    }

    @Override
    public Future<Connection> openFuture() {
        return openFuture;
    }

    @Override
    public Future<Connection> close() {
        if (CLOSED_UPDATER.compareAndSet(this, 0, 1)) {
            if (executor != null && !executor.isShutdown()) {
                executor.execute(() -> {
                    try {
                        if (protonConnection != null) {
                            protonConnection.close();
                        }
                    } catch (Throwable ignored) {
                        protonConnection = null;
                    }

                    // When already closed or a failure on write of close we can just shutdown the transport
                    if (protonConnection == null || protonConnection.getRemoteState() == ConnectionState.CLOSED) {
                        try {
                            transport.close();
                        } catch (IOException ignore) {}

                        closeFuture.complete(this);
                    }
                });

                if (options.closeTimeout() > 0) {
                    // Ensure transport gets shut down and future completed if remote doesn't respond.
                    executor.schedule(() -> {
                        try {
                            transport.close();
                        } catch (Throwable ignore) {}

                        closeFuture.complete(this);
                    }, options.closeTimeout(), TimeUnit.MILLISECONDS);
                }
            } else {
                closeFuture.complete(this);
            }
        }

        return closeFuture;
    }

    @Override
    public Session defaultSession() throws ClientException {
        checkClosed();
        final ClientFuture<Session> defaultSession = getFutureFactory().createFuture();

        executor.execute(() -> {
            try {
                checkClosed();
                defaultSession.complete(lazyCreateConnectionSession());
            } catch (Throwable error) {
                defaultSession.failed(ClientExceptionSupport.createNonFatalOrPassthrough(error));
            }
        });

        return request(defaultSession, options.requestTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Session openSession() throws ClientException {
        return openSession(getDefaultSessionOptions());
    }

    @Override
    public Session openSession(SessionOptions sessionOptions) throws ClientException {
        checkClosed();
        final ClientFuture<Session> createSession = getFutureFactory().createFuture();
        final SessionOptions sessionOpts = sessionOptions == null ? getDefaultSessionOptions() : sessionOptions;

        executor.execute(() -> {
            try {
                checkClosed();
                ClientSession session = new ClientSession(sessionOpts, ClientConnection.this, protonConnection.session());
                sessions.add(session);
                createSession.complete(session.open());
            } catch (Throwable error) {
                createSession.failed(ClientExceptionSupport.createNonFatalOrPassthrough(error));
            }
        });

        return request(createSession, options.requestTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Receiver openReceiver(String address) throws ClientException {
        return openReceiver(address, null);
    }

    @Override
    public Receiver openReceiver(String address, ReceiverOptions receiverOptions) throws ClientException {
        checkClosed();
        Objects.requireNonNull(address, "Cannot create a receiver with a null address");
        final ClientFuture<Receiver> createReceiver = getFutureFactory().createFuture();

        executor.execute(() -> {
            try {
                checkClosed();
                createReceiver.complete(lazyCreateConnectionSession().internalOpenReceiver(address, receiverOptions));
            } catch (Throwable error) {
                createReceiver.failed(ClientExceptionSupport.createNonFatalOrPassthrough(error));
            }
        });

        return request(createReceiver, options.requestTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Receiver openDynamicReceiver() throws ClientException {
        return openDynamicReceiver(null, null);
    }

    @Override
    public Receiver openDynamicReceiver(Map<String, Object> dynamicNodeProperties) throws ClientException {
        return openDynamicReceiver(null, null);
    }

    @Override
    public Receiver openDynamicReceiver(ReceiverOptions receiverOptions) throws ClientException {
        return openDynamicReceiver(null, null);
    }

    @Override
    public Receiver openDynamicReceiver(Map<String, Object> dynamicNodeProperties, ReceiverOptions receiverOptions) throws ClientException {
        checkClosed();
        final ClientFuture<Receiver> createReceiver = getFutureFactory().createFuture();

        executor.execute(() -> {
            try {
                checkClosed();
                createReceiver.complete(lazyCreateConnectionSession().internalOpenDynamicReceiver(dynamicNodeProperties, receiverOptions));
            } catch (Throwable error) {
                createReceiver.failed(ClientExceptionSupport.createNonFatalOrPassthrough(error));
            }
        });

        return request(createReceiver, options.requestTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Sender defaultSender() throws ClientException {
        checkClosed();
        final ClientFuture<Sender> defaultSender = getFutureFactory().createFuture();

        executor.execute(() -> {
            try {
                checkClosed();
                defaultSender.complete(lazyCreateConnectionSender());
            } catch (Throwable error) {
                defaultSender.failed(ClientExceptionSupport.createNonFatalOrPassthrough(error));
            }
        });

        return request(defaultSender, options.requestTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Sender openSender(String address) throws ClientException {
        return openSender(address, null);
    }

    @Override
    public Sender openSender(String address, SenderOptions senderOptions) throws ClientException {
        checkClosed();
        Objects.requireNonNull(address, "Cannot create a sender with a null address");
        final ClientFuture<Sender> createSender = getFutureFactory().createFuture();

        executor.execute(() -> {
            try {
                checkClosed();
                createSender.complete(lazyCreateConnectionSession().internalOpenSender(address, senderOptions));
            } catch (Throwable error) {
                createSender.failed(ClientExceptionSupport.createNonFatalOrPassthrough(error));
            }
        });

        return request(createSender, options.requestTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Sender openAnonymousSender() throws ClientException {
        return openAnonymousSender(null);
    }

    @Override
    public Sender openAnonymousSender(SenderOptions senderOptions) throws ClientException {
        checkClosed();
        final ClientFuture<Sender> createSender = getFutureFactory().createFuture();

        executor.execute(() -> {
            try {
                checkClosed();
                createSender.complete(lazyCreateConnectionSession().internalOpenAnonymousSender(senderOptions));
            } catch (Throwable error) {
                createSender.failed(ClientExceptionSupport.createNonFatalOrPassthrough(error));
            }
        });

        return request(createSender, options.requestTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Tracker send(Message<?> message) throws ClientException {
        checkClosed();
        Objects.requireNonNull(message, "Cannot send a null message");
        final ClientFuture<Tracker> result = getFutureFactory().createFuture();

        executor.execute(() -> {
            try {
                checkClosed();
                result.complete(lazyCreateConnectionSender().send(message));
            } catch (Throwable error) {
                result.failed(ClientExceptionSupport.createNonFatalOrPassthrough(error));
            }
        });

        return request(result, options.sendTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Map<String, Object> properties() throws ClientException {
        waitForOpenToComplete();
        return ClientConversionSupport.toStringKeyedMap(protonConnection.getRemoteProperties());
    }

    @Override
    public String[] offeredCapabilities() throws ClientException {
        waitForOpenToComplete();
        return ClientConversionSupport.toStringArray(protonConnection.getRemoteOfferedCapabilities());
    }

    @Override
    public String[] desiredCapabilities() throws ClientException {
        waitForOpenToComplete();
        return ClientConversionSupport.toStringArray(protonConnection.getRemoteDesiredCapabilities());
    }

    //----- Internal API

    String getId() {
        return connectionId;
    }

    ClientConnection connect() {
        try {
            executor = transport.connect(() -> {

                engine.configuration().setBufferAllocator(transport.getBufferAllocator());
                engine.outputHandler(toWrite -> handleEngineOutput(toWrite))
                      .errorHandler(error -> handleEngineErrors(error));

                configureEngineSaslSupport();

                protonConnection = engine.start();
                configureConnection(protonConnection);
                protonConnection.openHandler(connection -> handleRemoteOpen(connection))
                                .closeHandler(connection -> handleRemotecClose(connection));

            });
        } catch (Throwable e) {
            try {
                transport.close();
            } catch (Throwable t) {
                LOG.trace("close of transport reported error", t);
            }

            CLOSED_UPDATER.set(this, 1);
            FAILURE_CAUSE_UPDATER.compareAndSet(this, null, ClientExceptionSupport.createOrPassthroughFatal(e));

            openFuture.failed(ClientExceptionSupport.createNonFatalOrPassthrough(e));
            closeFuture.complete(this);
        }

        return this;
    }

    ClientConnection open() throws ClientException {
        checkClosed();
        executor.execute(() -> {
            if (engine.isShutdown()) {
                return;
            }

            // TODO - We aren't currently handling exceptions from the proton API methods
            //        in any meaningful way so eventually we need to get round to doing that
            //        From limited use of the API the current exception model may be a bit
            //        to vague and we may need to consider checked exceptions or at least
            //        some structured exceptions from the engine.
            // TODO - Possible issue with tick kicking in and writing idle frames before remote
            //        Open actually received.
            try {
                if (client.containerId() != null) {
                    protonConnection.setContainerId(client.containerId());
                }

                protonConnection.open().tickAuto(executor);

                if (options.openTimeout() > 0) {
                    executor.schedule(() -> {
                        if (!openFuture.isDone()) {
                            handleClientIOException(new ClientOperationTimedOutException(
                                "Connection Open timed out waiting for remote to open"));
                        }
                    }, options.openTimeout(), TimeUnit.MILLISECONDS);
                }
            } catch (Throwable error) {
                LOG.trace("Error from proton engine during connection open", error);
                handleClientIOException(ClientExceptionSupport.createOrPassthroughFatal(error));
            }
        });

        return this;
    }

    boolean isClosed() {
        return closed > 0;
    }

    ScheduledExecutorService getScheduler() {
        return executor;
    }

    Engine getEngine() {
        return engine;
    }

    ClientFutureFactory getFutureFactory() {
        return futureFactoy;
    }

    ClientException getFailureCause() {
        return failureCause;
    }

    ConnectionOptions getOptions() {
        return options;
    }

    ClientConnectionCapabilities getCapabilities() {
        return capabilities;
    }

    org.apache.qpid.proton4j.engine.Connection getProtonConnection() {
        return protonConnection;
    }

    String nextSessionId() {
        return getId() + ":" + sessionCounter.incrementAndGet();
    }

    <T> T request(ClientFuture<T> request, long timeout, TimeUnit units) throws ClientException {
        requests.put(request, request);

        try {
            if (timeout > 0) {
                return request.get(timeout, units);
            } else {
                return request.get();
            }
        } catch (Throwable error) {
            throw ClientExceptionSupport.createNonFatalOrPassthrough(error);
        } finally {
            requests.remove(request);
        }
    }

    ScheduledFuture<?> scheduleRequestTimeout(final AsyncResult<?> request, long timeout, final ClientException error) {
        if (timeout != INFINITE) {
            return executor.schedule(() -> request.failed(error), timeout, TimeUnit.MILLISECONDS);
        } else {
            return null;
        }
    }

    ScheduledFuture<?> scheduleRequestTimeout(final AsyncResult<?> request, long timeout, Supplier<ClientException> errorSupplier) {
        if (timeout != INFINITE) {
            return executor.schedule(() -> request.failed(errorSupplier.get()), timeout, TimeUnit.MILLISECONDS);
        } else {
            return null;
        }
    }

    void handleClientIOException(Throwable error) {
        handleClientIOException(ClientExceptionSupport.createOrPassthroughFatal(error));
    }

    void handleClientIOException(ClientException error) {
        CLOSED_UPDATER.set(this, 1);
        FAILURE_CAUSE_UPDATER.compareAndSet(this, null, error);
        try {
            try {
                protonConnection.close();
            } catch (Throwable ignored) {
            }

            try {
                engine.shutdown();
            } catch (Throwable ingore) {}

            try {
                transport.close();
            } catch (IOException ignored) {
            }

            sessions.forEach((session) -> {
                session.connectionClosed(error);
            });

            // Signal any waiters that the operation is done due to error.
            openFuture.failed(error);
            closeFuture.complete(ClientConnection.this);
        } catch (Throwable ingored) {
            LOG.trace("Ignoring error while closing down from client internal exception: ", ingored);
        }
    }

    //----- Private implementation events handlers and utility methods

    private void handleRemoteOpen(org.apache.qpid.proton4j.engine.Connection connection) {
        capabilities.determineCapabilities(connection);
        openFuture.complete(this);
    }

    private void handleRemotecClose(org.apache.qpid.proton4j.engine.Connection connection) {
        // TODO - On remote close we need to ensure that sessions and their resources
        //        all reflect the fact that they are now closed.  Also there is not a
        //        way currently to reflect the fact that a remote closed happened in
        //        the existing imperative client API.

        // Close should be idempotent so we can just respond here with a close in case
        // of remotely closed connection.  We should set error state from remote though
        // so client can see it.
        try {
            connection.close();
        } catch (Throwable ignored) {
            LOG.trace("Error on attempt to close proton connection was ignored");
        }

        final ClientException ex;

        if (connection.getRemoteCondition() != null) {
            ex = ClientErrorSupport.convertToConnectionClosedException(this, connection.getRemoteCondition());
        } else {
            ex = new ClientConnectionRemotelyClosedException("Remote closed with error");
        }

        handleClientIOException(ex);
    }

    private void handleEngineOutput(ProtonBuffer output) {
        try {
            transport.writeAndFlush(output);
        } catch (IOException e) {
            LOG.warn("Error while writing engine output to transport:", e);
            // TODO - Engine should handle thrown errors but we already see this one, we could just throw
            //        an Unchecked IOException here and let normal processing handle the error on the call
            //        chain and the error handler callback,
            handleEngineErrors(e);
        }
    }

    private void handleEngineErrors(Throwable error) {
        // TODO - Better handle errors and let all tracked resources know about them
        try {
            // Engine encountered critical error, shutdown.
            transport.close();
        } catch (IOException e) {
            LOG.error("Engine encountered critical error", error);
        } finally {
            CLOSED_UPDATER.lazySet(this, 1);
            openFuture.failed(new ClientException("Engine encountered critical error", error));
            closeFuture.complete(this);
        }
    }

    private Engine configureEngineSaslSupport() {
        if (options.saslEnabled()) {
            SaslMechanismSelector mechSelector =
                new SaslMechanismSelector(ClientConversionSupport.toSymbolSet(options.allowedMechanisms()));

            engine.saslContext().client().setListener(new SaslAuthenticator(mechSelector, new SaslCredentialsProvider() {

                @Override
                public String vhost() {
                    return options.vhost();
                }

                @Override
                public String username() {
                    return options.user();
                }

                @Override
                public String password() {
                    return options.password();
                }

                @Override
                public Principal localPrincipal() {
                    return transport.getLocalPrincipal();
                }
            }));
        }

        return engine;
    }

    private void configureConnection(org.apache.qpid.proton4j.engine.Connection protonConnection) {
        protonConnection.setChannelMax(options.channelMax());
        protonConnection.setMaxFrameSize(options.maxFrameSize());
        protonConnection.setHostname(transport.getHost());
        protonConnection.setIdleTimeout((int) options.idleTimeout());
        protonConnection.setOfferedCapabilities(ClientConversionSupport.toSymbolArray(options.offeredCapabilities()));
        protonConnection.setDesiredCapabilities(ClientConversionSupport.toSymbolArray(options.desiredCapabilities()));
        protonConnection.setProperties(ClientConversionSupport.toSymbolKeyedMap(options.properties()));
    }

    private ClientSession lazyCreateConnectionSession() {
        if (connectionSession == null) {
            connectionSession = new ClientSession(getDefaultSessionOptions(), this, protonConnection.session());
            sessions.add(connectionSession);
            try {
                connectionSession.open();
             } catch (Throwable error) {
                 sessions.remove(connectionSession);
             }
        }

        return connectionSession;
    }

    private Sender lazyCreateConnectionSender() throws ClientException {
        if (connectionSender == null) {
            if (openFuture.isComplete()) {
                checkAnonymousRelaySupported();
            }

            connectionSender = lazyCreateConnectionSession().internalOpenAnonymousSender(null);
            connectionSender.remotelyClosedHandler((sender) -> {
                try {
                    sender.close();
                } catch (Throwable ignore) {}

                // Clear the old closed sender, a lazy create needs to construct a new sender.
                connectionSender = null;
            });
        }

        return connectionSender;
    }

    void checkAnonymousRelaySupported() throws ClientUnsupportedOperationException {
        if (!capabilities.anonymousRelaySupported()) {
            throw new ClientUnsupportedOperationException("Anonymous relay support not available from this connection");
        }
    }

    protected void checkClosed() throws ClientClosedException {
        if (CLOSED_UPDATER.get(this) > 0) {
            if (failureCause != null) {
                throw new ClientClosedException("The Connection failed", failureCause);
            } else {
                throw new ClientClosedException("The Connection is closed");
            }
        }
    }

    private void waitForOpenToComplete() throws ClientException {
        if (!openFuture.isComplete() || openFuture.isFailed()) {
            try {
                openFuture.get();
            } catch (ExecutionException | InterruptedException e) {
                Thread.interrupted();
                if (failureCause != null) {
                    throw failureCause;
                } else {
                    throw ClientExceptionSupport.createNonFatalOrPassthrough(e.getCause());
                }
            }
        }
    }

    /*
     * Session options used when none specified by the caller creating a new session.
     */
    private SessionOptions getDefaultSessionOptions() {
        SessionOptions sessionOptions = defaultSessionOptions;
        if (sessionOptions == null) {
            synchronized (this) {
                sessionOptions = defaultSessionOptions;
                if (sessionOptions == null) {
                    sessionOptions = new SessionOptions();
                    sessionOptions.openTimeout(options.openTimeout());
                    sessionOptions.closeTimeout(options.closeTimeout());
                    sessionOptions.requestTimeout(options.requestTimeout());
                    sessionOptions.sendTimeout(options.sendTimeout());
                }

                defaultSessionOptions = sessionOptions;
            }
        }

        return sessionOptions;
    }
}
