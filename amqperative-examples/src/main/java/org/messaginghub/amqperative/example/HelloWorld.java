/*
 *
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
 *
 */
package org.messaginghub.amqperative.example;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.messaginghub.amqperative.Connection;
import org.messaginghub.amqperative.Message;
import org.messaginghub.amqperative.Receiver;
import org.messaginghub.amqperative.Sender;
import org.messaginghub.amqperative.Tracker;

public class HelloWorld {

    public static void main(String[] args) throws Exception {
        List<Message> messages = IntStream.range(1, 10).mapToObj(i -> new Message(String.format("Message %s", i)))
                .collect(Collectors.toList());

        // == Receive ==

        Connection conn = Connection.createConnection("localhost");
        Sender sender = conn.createSender("address");

        List<Tracker> trackers = new ArrayList<>();
        for(Message msg : messages) {
            trackers.add(sender.send(msg));
        }

        for(Tracker tracker : trackers) {
            System.out.println(tracker.get().getState());
        }

        // == Receive ==

        Receiver receiver = conn.createReceiver("address");
        receiver.deliveries().forEach(delivery -> {
            try {
                delivery.get().getPayload();
            } catch (Exception e) {
                e.printStackTrace();// TODO
            }
        });
    }
}
