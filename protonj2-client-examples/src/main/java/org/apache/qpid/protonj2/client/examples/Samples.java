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
package org.apache.qpid.protonj2.client.examples;

import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.qpid.protonj2.client.Client;
import org.apache.qpid.protonj2.client.ClientOptions;
import org.apache.qpid.protonj2.client.Connection;
import org.apache.qpid.protonj2.client.ConnectionOptions;
import org.apache.qpid.protonj2.client.Delivery;
import org.apache.qpid.protonj2.client.DurabilityMode;
import org.apache.qpid.protonj2.client.ExpiryPolicy;
import org.apache.qpid.protonj2.client.Message;
import org.apache.qpid.protonj2.client.Receiver;
import org.apache.qpid.protonj2.client.ReceiverOptions;
import org.apache.qpid.protonj2.client.Sender;
import org.apache.qpid.protonj2.client.SenderOptions;
import org.apache.qpid.protonj2.client.Tracker;

public class Samples {

    @SuppressWarnings("unused")
    public static void main(String[] args) throws Exception {
        if (args.length < 10) {
            throw new IllegalStateException("This clas isnt meant to be run, its for textual examples usage");
        }

        String brokerHost = "localhost";
        int brokerPort = 5672;
        String address = "examples";

        // =============== Create a client ===========

        Client client = Client.create();

        ClientOptions options = new ClientOptions();
        options.id(UUID.randomUUID().toString());
        Client client2 = Client.create(options); // With options

        // =============== Create a connection ===========

        Connection connection = client.connect(brokerHost, brokerPort); // host + port

        Connection connection2 = client.connect(brokerHost); // host only (port defaulted [maybe configurable at client?])

        ConnectionOptions connectionOptions = new ConnectionOptions();
        connectionOptions.user("myUsername");
        connectionOptions.password("myPassword");

        Connection connection3 = client.connect(brokerHost, brokerPort, connectionOptions); // host + port + options

        Connection connection4 = client.connect(brokerHost, connectionOptions); // host + options

        // =============== Create a sender ===========

        Sender sender = connection.openSender(address); //address-only
        sender.openFuture().get();

        SenderOptions senderOptions = new SenderOptions();
        senderOptions.targetOptions().capabilities("topic");
        senderOptions.sendTimeout(30_000);
        Sender sender2 = connection.openSender(address, senderOptions); // address and options

        // =============== Send a message ===========

        Message<String> message = Message.create("Hello World").durable(true);
        Tracker tracker = sender.send(message);

        // =============== Create a receiver ===========

        Receiver receiver = connection.openReceiver(address); //address-only
        receiver.openFuture().get();

        ReceiverOptions receiverOptions = new ReceiverOptions();
        //receiverOptions.setCreditWindow(50); // Defaults to 10. 0 disables automatic replenishment.
        receiverOptions.linkName("myLinkName");
        receiverOptions.sourceOptions().durabilityMode(DurabilityMode.CONFIGURATION);
        receiverOptions.sourceOptions().expiryPolicy(ExpiryPolicy.NEVER);
        receiverOptions.sourceOptions().capabilities("topic");
        Receiver receiver2 = connection.openReceiver(address, receiverOptions); // address and options

        // =============== Receive a message ===========

        Delivery delivery = receiver.receive(); // Waits forever
        Message<String> message1 = delivery.message();
        System.out.println(message1.body());
        delivery.accept(); // Or configure auto-accept?

        Delivery delivery2 = receiver.receive(5_000); // Waits with timeout

        Delivery delivery3 = receiver.tryReceive(); // Return delivery if available, null if not.

        // =============== Create a durable ===========

        Receiver durableReceiver = connection.openDurableReceiver("test-queue", "durable-sub");
        durableReceiver.openFuture().get();

        // =============== Create a dynamic receiver for request ===========

        Receiver dynamicReceiver = connection.openDynamicReceiver();
        dynamicReceiver.openFuture().get();
        String dynamicAddress = dynamicReceiver.address();

        Sender requestor = connection.openSender(address);
        Message<String> request = Message.create("Hello World").durable(true).replyTo(dynamicAddress);
        Tracker requestTracker = requestor.send(request);

        Delivery response = dynamicReceiver.receive(30_000);

        // =============== Close / Detach ===========

        Future<Sender> closeFuture = sender.close();
        closeFuture.get(5, TimeUnit.SECONDS);

        Future<Receiver> detachFuture = receiver.detach();
        detachFuture.get(5, TimeUnit.SECONDS);

        Future<Client> closeClientFuture = client.close();
        closeClientFuture.get(5, TimeUnit.SECONDS);
    }
}