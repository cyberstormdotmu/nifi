/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.standard;

import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.event.transport.EventException;
import org.apache.nifi.event.transport.EventServer;
import org.apache.nifi.event.transport.configuration.TransportProtocol;
import org.apache.nifi.event.transport.message.ByteArrayMessage;
import org.apache.nifi.event.transport.netty.ByteArrayMessageNettyEventServerFactory;
import org.apache.nifi.event.transport.netty.NettyEventServerFactory;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.DataUnit;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.processor.util.listen.EventBatcher;
import org.apache.nifi.processor.util.listen.FlowFileEventBatch;
import org.apache.nifi.processor.util.listen.ListenerProperties;
import org.apache.nifi.remote.io.socket.NetworkUtils;
import org.apache.nifi.security.util.ClientAuth;
import org.apache.nifi.ssl.RestrictedSSLContextService;
import org.apache.nifi.ssl.SSLContextService;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@SupportsBatching
@InputRequirement(InputRequirement.Requirement.INPUT_FORBIDDEN)
@Tags({"listen", "tcp", "tls", "ssl"})
@CapabilityDescription("Listens for incoming TCP connections and reads data from each connection using a line separator " +
        "as the message demarcator. The default behavior is for each message to produce a single FlowFile, however this can " +
        "be controlled by increasing the Batch Size to a larger value for higher throughput. The Receive Buffer Size must be " +
        "set as large as the largest messages expected to be received, meaning if every 100kb there is a line separator, then " +
        "the Receive Buffer Size must be greater than 100kb.")
@WritesAttributes({
        @WritesAttribute(attribute="tcp.sender", description="The sending host of the messages."),
        @WritesAttribute(attribute="tcp.port", description="The sending port the messages were received.")
})
public class ListenTCP extends AbstractProcessor {

    public static final PropertyDescriptor SSL_CONTEXT_SERVICE = new PropertyDescriptor.Builder()
            .name("SSL Context Service")
            .description("The Controller Service to use in order to obtain an SSL Context. If this property is set, " +
                    "messages will be received over a secure connection.")
            .required(false)
            .identifiesControllerService(RestrictedSSLContextService.class)
            .build();

    public static final PropertyDescriptor CLIENT_AUTH = new PropertyDescriptor.Builder()
            .name("Client Auth")
            .description("The client authentication policy to use for the SSL Context. Only used if an SSL Context Service is provided.")
            .required(false)
            .allowableValues(ClientAuth.values())
            .defaultValue(ClientAuth.REQUIRED.name())
            .build();

    // Deprecated
    public static final PropertyDescriptor MAX_RECV_THREAD_POOL_SIZE = new PropertyDescriptor.Builder()
            .name("max-receiving-threads")
            .displayName("Max Number of Receiving Message Handler Threads")
            .description(
                    "This property is deprecated and no longer used.")
            .addValidator(StandardValidators.createLongValidator(1, 65535, true))
            .required(false)
            .build();

    // Deprecated
    protected static final PropertyDescriptor POOL_RECV_BUFFERS = new PropertyDescriptor.Builder()
            .name("pool-receive-buffers")
            .displayName("Pool Receive Buffers")
            .description(
                    "This property is deprecated and no longer used.")
            .required(false)
            .defaultValue("True")
            .allowableValues("True", "False")
            .addValidator(StandardValidators.BOOLEAN_VALIDATOR)
            .build();

    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("Messages received successfully will be sent out this relationship.")
            .build();

    protected List<PropertyDescriptor> descriptors;
    protected Set<Relationship> relationships;
    protected volatile int port;
    protected volatile BlockingQueue<ByteArrayMessage> events;
    protected volatile BlockingQueue<ByteArrayMessage> errorEvents;
    protected volatile EventServer eventServer;
    protected volatile byte[] messageDemarcatorBytes;
    protected volatile EventBatcher<ByteArrayMessage> eventBatcher;

    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> descriptors = new ArrayList<>();
        descriptors.add(ListenerProperties.NETWORK_INTF_NAME);
        descriptors.add(ListenerProperties.PORT);
        descriptors.add(ListenerProperties.RECV_BUFFER_SIZE);
        descriptors.add(ListenerProperties.MAX_MESSAGE_QUEUE_SIZE);
        descriptors.add(ListenerProperties.MAX_SOCKET_BUFFER_SIZE);
        descriptors.add(ListenerProperties.CHARSET);
        descriptors.add(ListenerProperties.WORKER_THREADS);
        descriptors.add(ListenerProperties.MAX_BATCH_SIZE);
        descriptors.add(ListenerProperties.MESSAGE_DELIMITER);
        // Deprecated
        descriptors.add(MAX_RECV_THREAD_POOL_SIZE);
        // Deprecated
        descriptors.add(POOL_RECV_BUFFERS);
        descriptors.add(SSL_CONTEXT_SERVICE);
        descriptors.add(CLIENT_AUTH);
        this.descriptors = Collections.unmodifiableList(descriptors);

        final Set<Relationship> relationships = new HashSet<>();
        relationships.add(REL_SUCCESS);
        this.relationships = Collections.unmodifiableSet(relationships);
    }

    @OnScheduled
    public void onScheduled(ProcessContext context) throws IOException {
        int workerThreads = context.getProperty(ListenerProperties.WORKER_THREADS).asInteger();
        int bufferSize = context.getProperty(ListenerProperties.RECV_BUFFER_SIZE).asDataSize(DataUnit.B).intValue();
        int socketBufferSize = context.getProperty(ListenerProperties.MAX_SOCKET_BUFFER_SIZE).asDataSize(DataUnit.B).intValue();
        final String networkInterface = context.getProperty(ListenerProperties.NETWORK_INTF_NAME).evaluateAttributeExpressions().getValue();
        InetAddress address = NetworkUtils.getInterfaceAddress(networkInterface);
        Charset charset = Charset.forName(context.getProperty(ListenerProperties.CHARSET).getValue());
        port = context.getProperty(ListenerProperties.PORT).evaluateAttributeExpressions().asInteger();
        events = new LinkedBlockingQueue<>(context.getProperty(ListenerProperties.MAX_MESSAGE_QUEUE_SIZE).asInteger());
        errorEvents = new LinkedBlockingQueue<>();
        final String msgDemarcator = getMessageDemarcator(context);
        messageDemarcatorBytes = msgDemarcator.getBytes(charset);
        final NettyEventServerFactory eventFactory = new ByteArrayMessageNettyEventServerFactory(getLogger(), address, port, TransportProtocol.TCP, messageDemarcatorBytes, bufferSize, events);

        final SSLContextService sslContextService = context.getProperty(SSL_CONTEXT_SERVICE).asControllerService(SSLContextService.class);
        if (sslContextService != null) {
            final String clientAuthValue = context.getProperty(CLIENT_AUTH).getValue();
            ClientAuth clientAuth = ClientAuth.valueOf(clientAuthValue);
            SSLContext sslContext = sslContextService.createContext();
            eventFactory.setSslContext(sslContext);
            eventFactory.setClientAuth(clientAuth);
        }

        eventFactory.setSocketReceiveBuffer(socketBufferSize);
        eventFactory.setWorkerThreads(workerThreads);
        eventFactory.setThreadNamePrefix(String.format("%s[%s]", getClass().getSimpleName(), getIdentifier()));

        try {
            eventServer = eventFactory.getEventServer();
        } catch (EventException e) {
            getLogger().error("Failed to bind to [{}:{}]", address, port, e);
        }
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        final int batchSize = context.getProperty(ListenerProperties.MAX_BATCH_SIZE).asInteger();
        Map<String, FlowFileEventBatch<ByteArrayMessage>> batches = getEventBatcher().getBatches(session, batchSize, messageDemarcatorBytes);
        processEvents(session, batches);
    }

    private void processEvents(final ProcessSession session, final Map<String, FlowFileEventBatch<ByteArrayMessage>> batches) {
        for (Map.Entry<String, FlowFileEventBatch<ByteArrayMessage>> entry : batches.entrySet()) {
            FlowFile flowFile = entry.getValue().getFlowFile();
            final List<ByteArrayMessage> events = entry.getValue().getEvents();

            if (flowFile.getSize() == 0L || events.size() == 0) {
                session.remove(flowFile);
                getLogger().debug("No data written to FlowFile from batch {}; removing FlowFile", entry.getKey());
                continue;
            }

            final Map<String,String> attributes = getAttributes(entry.getValue());
            flowFile = session.putAllAttributes(flowFile, attributes);

            getLogger().debug("Transferring {} to success", flowFile);
            session.transfer(flowFile, REL_SUCCESS);
            session.adjustCounter("FlowFiles Transferred to Success", 1L, false);

            final String transitUri = getTransitUri(entry.getValue());
            session.getProvenanceReporter().receive(flowFile, transitUri);
        }
    }

    @OnStopped
    public void stopped() {
        if (eventServer != null) {
            eventServer.shutdown();
        }
        eventBatcher = null;
    }

    @Override
    protected Collection<ValidationResult> customValidate(final ValidationContext validationContext) {
        final List<ValidationResult> results = new ArrayList<>();

        final String clientAuth = validationContext.getProperty(CLIENT_AUTH).getValue();
        final SSLContextService sslContextService = validationContext.getProperty(SSL_CONTEXT_SERVICE).asControllerService(SSLContextService.class);

        if (sslContextService != null && StringUtils.isBlank(clientAuth)) {
            results.add(new ValidationResult.Builder()
                    .explanation("Client Auth must be provided when using TLS/SSL")
                    .valid(false).subject("Client Auth").build());
        }

        return results;
    }

    protected Map<String, String> getAttributes(final FlowFileEventBatch<ByteArrayMessage> batch) {
        final List<ByteArrayMessage> events = batch.getEvents();
        final String sender = events.get(0).getSender();
        final Map<String,String> attributes = new HashMap<>(3);
        attributes.put("tcp.sender", sender);
        attributes.put("tcp.port", String.valueOf(port));
        return attributes;
    }

    protected String getTransitUri(final FlowFileEventBatch<ByteArrayMessage> batch) {
        final List<ByteArrayMessage> events = batch.getEvents();
        final String sender = events.get(0).getSender();
        final String senderHost = sender.startsWith("/") && sender.length() > 1 ? sender.substring(1) : sender;
        return String.format("tcp://%s:%d", senderHost, port);
    }

    @Override
    public final Set<Relationship> getRelationships() {
        return this.relationships;
    }

    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return descriptors;
    }

    private String getMessageDemarcator(final ProcessContext context) {
        return context.getProperty(ListenerProperties.MESSAGE_DELIMITER)
                .getValue()
                .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
    }

    private EventBatcher<ByteArrayMessage> getEventBatcher() {
        if (eventBatcher == null) {
            eventBatcher = new EventBatcher<ByteArrayMessage>(getLogger(), events, errorEvents) {
                @Override
                protected String getBatchKey(ByteArrayMessage event) {
                    return event.getSender();
                }
            };
        }
        return eventBatcher;
    }
}