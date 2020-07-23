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
package org.apache.qpid.protonj2.client.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.apache.qpid.protonj2.buffer.ProtonBuffer;
import org.apache.qpid.protonj2.client.AdvancedMessage;
import org.apache.qpid.protonj2.client.Message;
import org.apache.qpid.protonj2.types.Binary;
import org.apache.qpid.protonj2.types.Symbol;
import org.apache.qpid.protonj2.types.messaging.ApplicationProperties;
import org.apache.qpid.protonj2.types.messaging.DeliveryAnnotations;
import org.apache.qpid.protonj2.types.messaging.Footer;
import org.apache.qpid.protonj2.types.messaging.Header;
import org.apache.qpid.protonj2.types.messaging.MessageAnnotations;
import org.apache.qpid.protonj2.types.messaging.Properties;
import org.apache.qpid.protonj2.types.messaging.Section;

public class ClientMessage<E> implements AdvancedMessage<E> {

    private Header header;
    private DeliveryAnnotations deliveryAnnotations;
    private MessageAnnotations messageAnnotations;
    private Properties properties;
    private ApplicationProperties applicationProperties;
    private Section<E> body;
    private Footer footer;

    private int messageFormat;
    private List<Section<E>> bodySections;

    /**
     * Create a new {@link ClientMessage} instance with no default body section or
     * section supplier
     *
     * @param sectionSupplier
     *      A {@link Supplier} that will generate Section values for the message body.
     */
    ClientMessage() {
        this.body = null;
    }

    /**
     * Create a new {@link ClientMessage} instance with a {@link Supplier} that will
     * provide the AMQP {@link Section} value for any body that is set on the message.
     *
     * @param body
     *      The object that comprises the value portion of the body {@link Section}.
     */
    ClientMessage(Section<E> body) {
        this.body = body;
    }

    @Override
    public AdvancedMessage<E> toAdvancedMessage() {
        return this;
    }

    //----- Entry point for creating new ClientMessage instances.

    /**
     * Creates an empty {@link ClientMessage} instance.
     *
     * @param <V> The type of the body value carried in this message.
     *
     * @return a new empty {@link ClientMessage} instance.
     */
    public static <V> ClientMessage<V> create() {
        return new ClientMessage<V>();
    }

    /**
     * Creates an {@link ClientMessage} instance with the given body {@link Section} value.
     *
     * @param <V> The type of the body value carried in this message body section.
     *
     * @param body
     *      The body {@link Section} to assign to the created message isntance.
     *
     * @return a new {@link ClientMessage} instance with the given body.
     */
    public static <V> ClientMessage<V> create(Section<V> body) {
        return new ClientMessage<V>(body);
    }

    /**
     * Creates an empty {@link ClientMessage} instance.
     *
     * @param <V> The type of the body value carried in this message.
     *
     * @return a new empty {@link ClientMessage} instance.
     */
    public static <V> ClientMessage<V> createAdvanvedMessage() {
        return new ClientMessage<V>();
    }

    //----- Message Header API

    @Override
    public boolean durable() {
        return header == null ? Header.DEFAULT_DURABILITY : header.isDurable();
    }

    @Override
    public ClientMessage<E> durable(boolean durable) {
        lazyCreateHeader().setDurable(durable);
        return this;
    }

    @Override
    public byte priority() {
        return header != null ? Header.DEFAULT_PRIORITY : header.getPriority();
    }

    @Override
    public ClientMessage<E> priority(byte priority) {
        lazyCreateHeader().setPriority(priority);
        return this;
    }

    @Override
    public long timeToLive() {
        return header != null ? Header.DEFAULT_TIME_TO_LIVE : header.getPriority();
    }

    @Override
    public ClientMessage<E> timeToLive(long timeToLive) {
        lazyCreateHeader().setTimeToLive(timeToLive);
        return this;
    }

    @Override
    public boolean firstAcquirer() {
        return header != null ? Header.DEFAULT_FIRST_ACQUIRER : header.isFirstAcquirer();
    }

    @Override
    public ClientMessage<E> firstAcquirer(boolean firstAcquirer) {
        lazyCreateHeader().setFirstAcquirer(firstAcquirer);
        return this;
    }

    @Override
    public long deliveryCount() {
        return header != null ? Header.DEFAULT_DELIVERY_COUNT : header.getDeliveryCount();
    }

    @Override
    public ClientMessage<E> deliveryCount(long deliveryCount) {
        lazyCreateHeader().setDeliveryCount(deliveryCount);
        return this;
    }

    //----- Message Properties access

    @Override
    public Object messageId() {
        return properties != null ? properties.getMessageId() : null;
    }

    @Override
    public Message<E> messageId(Object messageId) {
        lazyCreateProperties().setMessageId(messageId);
        return this;
    }

    @Override
    public byte[] userId() {
        byte[] copyOfUserId = null;
        if (properties != null && properties.getUserId() != null) {
            copyOfUserId = properties.getUserId().arrayCopy();
        }

        return copyOfUserId;
    }

    @Override
    public Message<E> userId(byte[] userId) {
        lazyCreateProperties().setUserId(new Binary(Arrays.copyOf(userId, userId.length)));
        return this;
    }

    @Override
    public String to() {
        return properties != null ? properties.getTo() : null;
    }

    @Override
    public Message<E> to(String to) {
        lazyCreateProperties().setTo(to);
        return this;
    }

    @Override
    public String subject() {
        return properties != null ? properties.getSubject() : null;
    }

    @Override
    public Message<E> subject(String subject) {
        lazyCreateProperties().setSubject(subject);
        return this;
    }

    @Override
    public String replyTo() {
        return properties != null ? properties.getReplyTo() : null;
    }

    @Override
    public Message<E> replyTo(String replyTo) {
        lazyCreateProperties().setReplyTo(replyTo);
        return this;
    }

    @Override
    public Object correlationId() {
        return properties != null ? properties.getCorrelationId() : null;
    }

    @Override
    public Message<E> correlationId(Object correlationId) {
        lazyCreateProperties().setCorrelationId(correlationId);
        return this;
    }

    @Override
    public String contentType() {
        return properties != null ? properties.getContentType() : null;
    }

    @Override
    public Message<E> contentType(String contentType) {
        lazyCreateProperties().setContentType(contentType);
        return this;
    }

    @Override
    public String contentEncoding() {
        return properties != null ? properties.getContentEncoding() : null;
    }

    @Override
    public Message<E> contentEncoding(String contentEncoding) {
        lazyCreateProperties().setContentEncoding(contentEncoding);
        return this;
    }

    @Override
    public long absoluteExpiryTime() {
        return properties != null ? properties.getAbsoluteExpiryTime() : null;
    }

    @Override
    public Message<E> absoluteExpiryTime(long expiryTime) {
        lazyCreateProperties().setAbsoluteExpiryTime(expiryTime);
        return this;
    }

    @Override
    public long creationTime() {
        return properties != null ? properties.getCreationTime() : null;
    }

    @Override
    public Message<E> creationTime(long createTime) {
        lazyCreateProperties().setCreationTime(createTime);
        return this;
    }

    @Override
    public String groupId() {
        return properties != null ? properties.getGroupId() : null;
    }

    @Override
    public Message<E> groupId(String groupId) {
        lazyCreateProperties().setGroupId(groupId);
        return this;
    }

    @Override
    public int groupSequence() {
        return properties != null ? (int) properties.getGroupSequence() : null;
    }

    @Override
    public Message<E> groupSequence(int groupSequence) {
        lazyCreateProperties().setGroupSequence(groupSequence);
        return this;
    }

    @Override
    public String replyToGroupId() {
        return properties != null ? properties.getReplyToGroupId() : null;
    }

    @Override
    public Message<E> replyToGroupId(String replyToGroupId) {
        lazyCreateProperties().setReplyToGroupId(replyToGroupId);
        return this;
    }

    //----- Delivery Annotations Access

    @Override
    public Object deliveryAnnotation(String key) {
        Object value = null;
        if (deliveryAnnotations != null && deliveryAnnotations.getValue() != null) {
            value = deliveryAnnotations.getValue().get(Symbol.valueOf(key));
        }

        return value;
    }

    @Override
    public boolean hasDeliveryAnnotation(String key) {
        if (deliveryAnnotations != null && deliveryAnnotations.getValue() != null) {
            return deliveryAnnotations.getValue().containsKey(Symbol.valueOf(key));
        } else {
            return false;
        }
    }

    @Override
    public boolean hasDeliveryAnnotations() {
        return deliveryAnnotations != null &&
               deliveryAnnotations.getValue() != null &&
               deliveryAnnotations.getValue().size() > 0;
    }

    @Override
    public Object removeDeliveryAnnotation(String key) {
        if (hasDeliveryAnnotations()) {
            return deliveryAnnotations.getValue().remove(Symbol.valueOf(key));
        } else {
            return null;
        }
     }

    @Override
    public Message<E> forEachDeliveryAnnotation(BiConsumer<String, Object> action) {
        if (hasDeliveryAnnotations()) {
            deliveryAnnotations.getValue().forEach((key, value) -> {
                action.accept(key.toString(), value);
            });
        }

        return this;
    }

    @Override
    public ClientMessage<E> deliveryAnnotation(String key, Object value) {
        lazyCreateDeliveryAnnotations().getValue().put(Symbol.valueOf(key),value);
        return this;
    }

    //----- Message Annotations Access

    @Override
    public Object messageAnnotation(String key) {
        Object value = null;
        if (messageAnnotations != null) {
            value = messageAnnotations.getValue().get(Symbol.valueOf(key));
        }

        return value;
    }

    @Override
    public boolean hasMessageAnnotation(String key) {
        if (messageAnnotations != null && messageAnnotations.getValue() != null) {
            return messageAnnotations.getValue().containsKey(Symbol.valueOf(key));
        } else {
            return false;
        }
    }

    @Override
    public boolean hasMessageAnnotations() {
        return messageAnnotations != null &&
               messageAnnotations.getValue() != null &&
               messageAnnotations.getValue().size() > 0;
    }

    @Override
    public Object removeMessageAnnotation(String key) {
        if (hasMessageAnnotations()) {
            return messageAnnotations.getValue().remove(Symbol.valueOf(key));
        } else {
            return null;
        }
     }

    @Override
    public Message<E> forEachMessageAnnotation(BiConsumer<String, Object> action) {
        if (hasMessageAnnotations()) {
            messageAnnotations.getValue().forEach((key, value) -> {
                action.accept(key.toString(), value);
            });
        }

        return this;
    }

    @Override
    public ClientMessage<E> messageAnnotation(String key, Object value) {
        lazyCreateMessageAnnotations().getValue().put(Symbol.valueOf(key),value);
        return this;
    }

    //----- Application Properties Access

    @Override
    public Object applicationProperty(String key) {
        Object value = null;
        if (applicationProperties != null) {
            value = applicationProperties.getValue().get(key);
        }

        return value;
    }

    @Override
    public boolean hasApplicationProperty(String key) {
        if (applicationProperties != null && applicationProperties.getValue() != null) {
            return applicationProperties.getValue().containsKey(key);
        } else {
            return false;
        }
    }

    @Override
    public boolean hasApplicationProperties() {
        return applicationProperties != null &&
               applicationProperties.getValue() != null &&
               applicationProperties.getValue().size() > 0;
    }

    @Override
    public Object removeApplicationProperty(String key) {
        if (hasApplicationProperties()) {
            return applicationProperties.getValue().remove(key);
        } else {
            return null;
        }
     }

    @Override
    public Message<E> forEachApplicationProperty(BiConsumer<String, Object> action) {
        if (hasApplicationProperties()) {
            applicationProperties.getValue().forEach(action);
        }

        return this;
    }

    @Override
    public ClientMessage<E> applicationProperty(String key, Object value) {
        lazyCreateApplicationProperties().getValue().put(key,value);
        return this;
    }

    //----- Footer Access

    @Override
    public Object footer(String key) {
        Object value = null;
        if (footer != null) {
            value = footer.getValue().get(Symbol.valueOf(key));
        }

        return value;
    }

    @Override
    public boolean hasFooter(String key) {
        if (footer != null && footer.getValue() != null) {
            return footer.getValue().containsKey(Symbol.valueOf(key));
        } else {
            return false;
        }
    }

    @Override
    public boolean hasFooters() {
        return footer != null &&
               footer.getValue() != null &&
               footer.getValue().size() > 0;
    }

    @Override
    public Object removeFooter(String key) {
        if (hasFooters()) {
            return footer.getValue().remove(Symbol.valueOf(key));
        } else {
            return null;
        }
     }

    @Override
    public Message<E> forEachFooter(BiConsumer<String, Object> action) {
        if (hasFooters()) {
            footer.getValue().forEach((key, value) -> {
                action.accept(key.toString(), value);
            });
        }

        return this;
    }

    @Override
    public ClientMessage<E> footer(String key, Object value) {
        lazyCreateFooter().getValue().put(Symbol.valueOf(key),value);
        return this;
    }

    //----- Message body access

    @Override
    public E body() {
        Section<E> section = body;

        if (bodySections != null) {
            if (bodySections.size() > 1) {
                throw new IllegalStateException("Cannot get a singleton body from Message with multiple body sections assigned");
            } else {
                section = bodySections.get(0);
            }
        }

        return section != null ? section.getValue() : null;
    }

    @Override
    public ClientMessage<E> body(E value) {
        if (bodySections != null) {
            if (bodySections.size() > 1) {
                throw new IllegalStateException("Cannot set a singleton body to a Message with multiple body sections assigned");
            } else {
                if (value != null) {
                    bodySections.set(0, ClientMessageSupport.createSectionFromValue(value));
                } else {
                    bodySections = null;
                }
            }
        } else {
            body = ClientMessageSupport.createSectionFromValue(value);
        }

        return this;
    }

    Section<E> getBodySection() {
        return body;
    }

    //----- Internal API

    private Header lazyCreateHeader() {
        if (header == null) {
            header = new Header();
        }

        return header;
    }

    private Properties lazyCreateProperties() {
        if (properties == null) {
            properties = new Properties();
        }

        return properties;
    }

    private ApplicationProperties lazyCreateApplicationProperties() {
        if (applicationProperties == null) {
            applicationProperties = new ApplicationProperties(new LinkedHashMap<>());
        }

        return applicationProperties;
    }

    private MessageAnnotations lazyCreateMessageAnnotations() {
        if (messageAnnotations == null) {
            messageAnnotations = new MessageAnnotations(new LinkedHashMap<>());
        }

        return messageAnnotations;
    }

    private DeliveryAnnotations lazyCreateDeliveryAnnotations() {
        if (deliveryAnnotations == null) {
            deliveryAnnotations = new DeliveryAnnotations(new LinkedHashMap<>());
        }

        return deliveryAnnotations;
    }

    private Footer lazyCreateFooter() {
        if (footer == null) {
            footer = new Footer(new LinkedHashMap<>());
        }

        return footer;
    }

    //----- AdvancedMessage interface implementation

    @Override
    public Header header() {
        return header;
    }

    @Override
    public AdvancedMessage<E> header(Header header) {
        this.header = header;
        return this;
    }

    @Override
    public DeliveryAnnotations deliveryAnnotations() {
        return deliveryAnnotations;
    }

    @Override
    public AdvancedMessage<E> deliveryAnnotations(DeliveryAnnotations deliveryAnnotations) {
        this.deliveryAnnotations = deliveryAnnotations;
        return this;
    }

    @Override
    public MessageAnnotations messageAnnotations() {
        return messageAnnotations;
    }

    @Override
    public AdvancedMessage<E> messageAnnotations(MessageAnnotations messageAnnotations) {
        this.messageAnnotations = messageAnnotations;
        return this;
    }

    @Override
    public Properties properties() {
        return properties;
    }

    @Override
    public AdvancedMessage<E> properties(Properties properties) {
        this.properties = properties;
        return this;
    }

    @Override
    public ApplicationProperties applicationProperties() {
        return applicationProperties;
    }

    @Override
    public AdvancedMessage<E> applicationProperties(ApplicationProperties applicationProperties) {
        this.applicationProperties = applicationProperties;
        return this;
    }

    @Override
    public Footer footer() {
        return footer;
    }

    @Override
    public AdvancedMessage<E> footer(Footer footer) {
        this.footer = footer;
        return this;
    }

    @Override
    public int messageFormat() {
        return messageFormat;
    }

    @Override
    public AdvancedMessage<E> messageFormat(int messageFormat) {
        this.messageFormat = messageFormat;
        return this;
    }

    @Override
    public ProtonBuffer encode() {
        return ClientMessageSupport.encodeMessage(this);
    }

    @Override
    public AdvancedMessage<E> addBodySection(Section<E> bodySection) {
        Objects.requireNonNull(bodySection, "Additional Body Section cannot be null");

        if (bodySections == null) {
            bodySections = new ArrayList<>();

            // Preserve older section from original message creation.
            if (body != null) {
                bodySections.add(body);
                body = null;
            }
        }

        return this;
    }

    @Override
    public AdvancedMessage<E> bodySections(Collection<Section<E>> sections) {
        if (sections == null || sections.isEmpty()) {
            this.bodySections = null;
        } else {
            this.bodySections = new ArrayList<>(sections);
        }

        return this;
    }

    @Override
    public Collection<Section<E>> bodySections() {
        final Collection<Section<E>> result = new ArrayList<>();
        forEachBodySection(section -> result.add(section));
        return Collections.unmodifiableCollection(result);
    }

    @Override
    public AdvancedMessage<E> forEachBodySection(Consumer<Section<E>> consumer) {
        if (bodySections != null) {
            bodySections.forEach(section -> {
                consumer.accept(section);
            });
        } else {
            if (body != null) {
                consumer.accept(body);
            }
        }

        return this;
    }
}
