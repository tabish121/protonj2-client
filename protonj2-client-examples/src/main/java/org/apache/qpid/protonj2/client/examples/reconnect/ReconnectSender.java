/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.qpid.protonj2.client.examples.reconnect;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import org.apache.qpid.protonj2.client.Client;
import org.apache.qpid.protonj2.client.ClientOptions;
import org.apache.qpid.protonj2.client.Connection;
import org.apache.qpid.protonj2.client.ConnectionOptions;
import org.apache.qpid.protonj2.client.Message;
import org.apache.qpid.protonj2.client.Sender;
import org.apache.qpid.protonj2.client.exceptions.ClientException;
import org.apache.qpid.protonj2.client.exceptions.ClientIOException;

public class ReconnectSender {

    private static final int MESSAGE_COUNT = 10;

    private String brokerHost = "localhost";
    private int brokerPort = 5672;
    private String address = "examples";

    private final CountDownLatch allMessagesSent = new CountDownLatch(MESSAGE_COUNT);

    private Connection connection;

    public static void main(String[] args) throws Exception {
        try {
            ReconnectSender sender = new ReconnectSender();

            sender.start().awaitSendComplete();
        } catch (Exception exp) {
            System.out.println("Caught exception, exiting.");
            exp.printStackTrace(System.out);
            System.exit(1);
        }
    }

    public ReconnectSender start() throws ClientException {
        ClientOptions options = new ClientOptions();
        options.id(UUID.randomUUID().toString());
        Client client = Client.create(options);

        ConnectionOptions connectionOpts = new ConnectionOptions();
        connectionOpts.reconnectEnabled(true);
        connectionOpts.connectedHandler((connection, event) -> sendMessages(connection));

        connection = client.connect(brokerHost, brokerPort, connectionOpts);

        return this;
    }

    public void sendMessages(Connection connection) {
        Sender sender = null;

        while (allMessagesSent.getCount() != 0) {
            try {
                if (sender == null) {
                    sender = connection.openSender(address);
                }

                sender.send(Message.create("Hello World")).awaitSettlement();

                allMessagesSent.countDown();
            } catch (ClientIOException e) {
                // Remote connection failed, next connection event will restart sends.
                return;
            } catch (ClientException e) {
                // Local side error occurred, recreate sender and try again.
                sender.close();
                sender = null;
            }
        }
    }

    public void awaitSendComplete() throws InterruptedException {
        allMessagesSent.await();
        connection.close();
    }
}
