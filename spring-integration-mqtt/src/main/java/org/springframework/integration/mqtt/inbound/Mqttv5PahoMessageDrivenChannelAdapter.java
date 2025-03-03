/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.mqtt.inbound;

import java.util.Arrays;
import java.util.Map;

import org.eclipse.paho.mqttv5.client.IMqttAsyncClient;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClientPersistence;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.integration.IntegrationMessageHeaderAccessor;
import org.springframework.integration.acks.SimpleAcknowledgment;
import org.springframework.integration.context.IntegrationContextUtils;
import org.springframework.integration.mapping.HeaderMapper;
import org.springframework.integration.mqtt.core.MqttComponent;
import org.springframework.integration.mqtt.event.MqttConnectionFailedEvent;
import org.springframework.integration.mqtt.event.MqttProtocolErrorEvent;
import org.springframework.integration.mqtt.event.MqttSubscribedEvent;
import org.springframework.integration.mqtt.support.MqttHeaderMapper;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.integration.mqtt.support.MqttMessageConverter;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.converter.SmartMessageConverter;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.util.Assert;

/**
 * The {@link AbstractMqttMessageDrivenChannelAdapter} implementation for MQTT v5.
 *
 * The {@link MqttProperties} are mapped via the provided {@link HeaderMapper};
 * meanwhile the regular {@link MqttMessage} properties are always mapped into headers.
 *
 * It is recommended to have the {@link MqttConnectionOptions#setAutomaticReconnect(boolean)}
 * set to true to let an internal {@link IMqttAsyncClient} instance to handle reconnects.
 * Otherwise, only the manual restart of this component can handle reconnects, e.g. via
 * {@link MqttConnectionFailedEvent} handling on disconnection.
 *
 * See {@link #setPayloadType} for more information about type conversion.
 *
 * @author Artem Bilan
 *
 * @since 5.5.5
 *
 */
public class Mqttv5PahoMessageDrivenChannelAdapter extends AbstractMqttMessageDrivenChannelAdapter
		implements MqttCallback, MqttComponent<MqttConnectionOptions> {

	private final MqttConnectionOptions connectionOptions;

	private IMqttAsyncClient mqttClient;

	@Nullable
	private MqttClientPersistence persistence;

	private SmartMessageConverter messageConverter;

	private Class<?> payloadType = byte[].class;

	private HeaderMapper<MqttProperties> headerMapper = new MqttHeaderMapper();

	public Mqttv5PahoMessageDrivenChannelAdapter(String url, String clientId, String... topic) {
		super(url, clientId, topic);
		this.connectionOptions = new MqttConnectionOptions();
		this.connectionOptions.setServerURIs(new String[]{ url });
		this.connectionOptions.setAutomaticReconnect(true);
	}

	public Mqttv5PahoMessageDrivenChannelAdapter(MqttConnectionOptions connectionOptions, String clientId,
			String... topic) {

		super(obtainServerUrlFromOptions(connectionOptions), clientId, topic);
		this.connectionOptions = connectionOptions;
		if (!this.connectionOptions.isAutomaticReconnect()) {
			logger.warn("It is recommended to set 'automaticReconnect' MQTT client option. " +
					"Otherwise the current channel adapter restart should be used explicitly, " +
					"e.g. via handling 'MqttConnectionFailedEvent' on client disconnection.");
		}
	}

	@Override
	public MqttConnectionOptions getConnectionInfo() {
		return this.connectionOptions;
	}

	public void setPersistence(@Nullable MqttClientPersistence persistence) {
		this.persistence = persistence;
	}

	@Override
	public void setConverter(MqttMessageConverter converter) {
		throw new UnsupportedOperationException("Use setMessageConverter(SmartMessageConverter) instead");
	}

	public void setMessageConverter(SmartMessageConverter messageConverter) {
		this.messageConverter = messageConverter;
	}

	/**
	 * Set the type of the target message payload to produce after conversion from MQTT message.
	 * Defaults to {@code byte[].class} - just extract MQTT message payload without conversion.
	 * Can be set to {@link MqttMessage} class to produce the whole MQTT message as a payload.
	 * @param payloadType the expected payload type to convert MQTT message to.
	 */
	public void setPayloadType(Class<?> payloadType) {
		Assert.notNull(payloadType, "'payloadType' must not be null.");
		this.payloadType = payloadType;
	}

	public void setHeaderMapper(HeaderMapper<MqttProperties> headerMapper) {
		Assert.notNull(headerMapper, "'headerMapper' must not be null.");
		this.headerMapper = headerMapper;
	}

	@Override
	protected void onInit() {
		super.onInit();
		try {
			this.mqttClient = new MqttAsyncClient(getUrl(), getClientId(), this.persistence);
			this.mqttClient.setCallback(this);
			this.mqttClient.setManualAcks(isManualAcks());
		}
		catch (MqttException ex) {
			throw new BeanCreationException("Cannot create 'MqttAsyncClient' for: " + getComponentName(), ex);
		}
		if (this.messageConverter == null) {
			setMessageConverter(getBeanFactory()
					.getBean(IntegrationContextUtils.ARGUMENT_RESOLVER_MESSAGE_CONVERTER_BEAN_NAME,
							SmartMessageConverter.class));
		}
	}

	@Override
	protected void doStart() {
		ApplicationEventPublisher applicationEventPublisher = getApplicationEventPublisher();
		this.topicLock.lock();
		String[] topics = getTopic();
		try {
			this.mqttClient.connect(this.connectionOptions).waitForCompletion(getCompletionTimeout());
			if (topics.length > 0) {
				int[] requestedQos = getQos();
				this.mqttClient.subscribe(topics, requestedQos).waitForCompletion(getCompletionTimeout());
				String message = "Connected and subscribed to " + Arrays.toString(topics);
				logger.debug(message);
				if (applicationEventPublisher != null) {
					applicationEventPublisher.publishEvent(new MqttSubscribedEvent(this, message));
				}
			}
		}
		catch (MqttException ex) {
			if (applicationEventPublisher != null) {
				applicationEventPublisher.publishEvent(new MqttConnectionFailedEvent(this, ex));
			}
			logger.error(ex, () -> "Error connecting or subscribing to " + Arrays.toString(topics));
		}
		finally {
			this.topicLock.unlock();
		}
	}

	@Override
	protected void doStop() {
		this.topicLock.lock();
		String[] topics = getTopic();
		try {
			this.mqttClient.unsubscribe(topics).waitForCompletion(getCompletionTimeout());
			this.mqttClient.disconnect().waitForCompletion(getCompletionTimeout());
		}
		catch (MqttException ex) {
			logger.error(ex, () -> "Error unsubscribing from " + Arrays.toString(topics));
		}
		finally {
			this.topicLock.unlock();
		}
	}

	@Override
	public void destroy() {
		super.destroy();
		try {
			this.mqttClient.close(true);
		}
		catch (MqttException ex) {
			logger.error(ex, "Failed to close 'MqttAsyncClient'");
		}
	}

	@Override
	public void addTopic(String topic, int qos) {
		this.topicLock.lock();
		try {
			this.mqttClient.subscribe(topic, qos).waitForCompletion(getCompletionTimeout());
			super.addTopic(topic, qos);
		}
		catch (MqttException ex) {
			throw new MessagingException("Failed to subscribe to topic " + topic, ex);
		}
		finally {
			this.topicLock.unlock();
		}
	}

	@Override
	public void removeTopic(String... topic) {
		this.topicLock.lock();
		try {
			this.mqttClient.unsubscribe(topic).waitForCompletion(getCompletionTimeout());
			super.removeTopic(topic);
		}
		catch (MqttException ex) {
			throw new MessagingException("Failed to unsubscribe from topic(s) " + Arrays.toString(topic), ex);
		}
		finally {
			this.topicLock.unlock();
		}
	}

	@Override
	public void messageArrived(String topic, MqttMessage mqttMessage) {
		Map<String, Object> headers = this.headerMapper.toHeaders(mqttMessage.getProperties());
		headers.put(MqttHeaders.ID, mqttMessage.getId());
		headers.put(MqttHeaders.RECEIVED_QOS, mqttMessage.getQos());
		headers.put(MqttHeaders.DUPLICATE, mqttMessage.isDuplicate());
		headers.put(MqttHeaders.RECEIVED_RETAINED, mqttMessage.isRetained());
		headers.put(MqttHeaders.RECEIVED_TOPIC, topic);

		if (isManualAcks()) {
			headers.put(IntegrationMessageHeaderAccessor.ACKNOWLEDGMENT_CALLBACK,
					new AcknowledgmentImpl(mqttMessage.getId(), mqttMessage.getQos(), this.mqttClient));
		}

		Object payload =
				MqttMessage.class.isAssignableFrom(this.payloadType)
						? mqttMessage
						: mqttMessage.getPayload();

		Message<?> message;
		if (MqttMessage.class.isAssignableFrom(this.payloadType) || byte[].class.isAssignableFrom(this.payloadType)) {
			message = new GenericMessage<>(payload, headers);
		}
		else {
			message = this.messageConverter.toMessage(payload, new MessageHeaders(headers), this.payloadType);
		}

		try {
			sendMessage(message);
		}
		catch (RuntimeException ex) {
			logger.error(ex, () -> "Unhandled exception for " + message);
			throw ex;
		}
	}

	@Override
	public void disconnected(MqttDisconnectResponse disconnectResponse) {
		MqttException cause = disconnectResponse.getException();
		ApplicationEventPublisher applicationEventPublisher = getApplicationEventPublisher();
		if (cause != null && applicationEventPublisher != null) {
			applicationEventPublisher.publishEvent(new MqttConnectionFailedEvent(this, cause));
		}
	}

	@Override
	public void mqttErrorOccurred(MqttException exception) {
		ApplicationEventPublisher applicationEventPublisher = getApplicationEventPublisher();
		if (applicationEventPublisher != null) {
			applicationEventPublisher.publishEvent(new MqttProtocolErrorEvent(this, exception));
		}
	}

	@Override
	public void deliveryComplete(IMqttToken token) {

	}

	@Override
	public void connectComplete(boolean reconnect, String serverURI) {

	}

	@Override
	public void authPacketArrived(int reasonCode, MqttProperties properties) {

	}

	private static String obtainServerUrlFromOptions(MqttConnectionOptions connectionOptions) {
		Assert.notNull(connectionOptions, "'connectionOptions' must not be null");
		String[] serverURIs = connectionOptions.getServerURIs();
		Assert.notEmpty(serverURIs, "'serverURIs' must be provided in the 'MqttConnectionOptions'");
		return serverURIs[0];
	}


	/**
	 * Used to complete message arrival when {@link #isManualAcks()} is true.
	 */
	private static class AcknowledgmentImpl implements SimpleAcknowledgment {

		private final int id;

		private final int qos;

		private final IMqttAsyncClient ackClient;

		/**
		 * Construct an instance with the provided properties.
		 * @param id the message id.
		 * @param qos the message QOS.
		 * @param client the client.
		 */
		AcknowledgmentImpl(int id, int qos, IMqttAsyncClient client) {
			this.id = id;
			this.qos = qos;
			this.ackClient = client;
		}

		@Override
		public void acknowledge() {
			try {
				this.ackClient.messageArrivedComplete(this.id, this.qos);
			}
			catch (MqttException ex) {
				throw new IllegalStateException(ex);
			}
		}

	}

}
