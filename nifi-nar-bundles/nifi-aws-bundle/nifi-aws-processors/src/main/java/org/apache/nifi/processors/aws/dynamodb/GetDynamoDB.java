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
package org.apache.nifi.processors.aws.dynamodb;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.dynamodbv2.document.BatchGetItemOutcome;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.TableKeysAndAttributes;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.KeysAndAttributes;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.ReadsAttribute;
import org.apache.nifi.annotation.behavior.ReadsAttributes;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.ConfigVerificationResult;
import org.apache.nifi.components.ConfigVerificationResult.Outcome;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@SupportsBatching
@SeeAlso({DeleteDynamoDB.class, PutDynamoDB.class})
@InputRequirement(Requirement.INPUT_REQUIRED)
@Tags({"Amazon", "DynamoDB", "AWS", "Get", "Fetch"})
@CapabilityDescription("Retrieves a document from DynamoDB based on hash and range key.  The key can be string or number."
        + "For any get request all the primary keys are required (hash or hash and range based on the table keys)."
        + "A Json Document ('Map') attribute of the DynamoDB item is read into the content of the FlowFile.")
@WritesAttributes({
    @WritesAttribute(attribute = AbstractDynamoDBProcessor.DYNAMODB_KEY_ERROR_UNPROCESSED, description = "Dynamo db unprocessed keys"),
    @WritesAttribute(attribute = AbstractDynamoDBProcessor.DYNAMODB_RANGE_KEY_VALUE_ERROR, description = "Dynamod db range key error"),
    @WritesAttribute(attribute = AbstractDynamoDBProcessor.DYNAMODB_KEY_ERROR_NOT_FOUND, description = "Dynamo db key not found"),
    @WritesAttribute(attribute = AbstractDynamoDBProcessor.DYNAMODB_ERROR_EXCEPTION_MESSAGE, description = "Dynamo db exception message"),
    @WritesAttribute(attribute = AbstractDynamoDBProcessor.DYNAMODB_ERROR_CODE, description = "Dynamo db error code"),
    @WritesAttribute(attribute = AbstractDynamoDBProcessor.DYNAMODB_ERROR_MESSAGE, description = "Dynamo db error message"),
    @WritesAttribute(attribute = AbstractDynamoDBProcessor.DYNAMODB_ERROR_TYPE, description = "Dynamo db error type"),
    @WritesAttribute(attribute = AbstractDynamoDBProcessor.DYNAMODB_ERROR_SERVICE, description = "Dynamo db error service"),
    @WritesAttribute(attribute = AbstractDynamoDBProcessor.DYNAMODB_ERROR_RETRYABLE, description = "Dynamo db error is retryable"),
    @WritesAttribute(attribute = AbstractDynamoDBProcessor.DYNAMODB_ERROR_REQUEST_ID, description = "Dynamo db error request id"),
    @WritesAttribute(attribute = AbstractDynamoDBProcessor.DYNAMODB_ERROR_STATUS_CODE, description = "Dynamo db status code")
    })
@ReadsAttributes({
    @ReadsAttribute(attribute = AbstractDynamoDBProcessor.DYNAMODB_ITEM_HASH_KEY_VALUE, description = "Items hash key value" ),
    @ReadsAttribute(attribute = AbstractDynamoDBProcessor.DYNAMODB_ITEM_RANGE_KEY_VALUE, description = "Items range key value" ),
    })
public class GetDynamoDB extends AbstractDynamoDBProcessor {

    public static final List<PropertyDescriptor> properties = Collections.unmodifiableList(
            Arrays.asList(TABLE, HASH_KEY_NAME, RANGE_KEY_NAME, HASH_KEY_VALUE, RANGE_KEY_VALUE,
                HASH_KEY_VALUE_TYPE, RANGE_KEY_VALUE_TYPE, JSON_DOCUMENT, BATCH_SIZE, REGION, ACCESS_KEY, SECRET_KEY,
                CREDENTIALS_FILE, AWS_CREDENTIALS_PROVIDER_SERVICE, TIMEOUT, SSL_CONTEXT_SERVICE,
                PROXY_CONFIGURATION_SERVICE, PROXY_HOST, PROXY_HOST_PORT, PROXY_USERNAME, PROXY_PASSWORD));

    public static final Relationship REL_NOT_FOUND = new Relationship.Builder().name("not found")
            .description("FlowFiles are routed to not found relationship if key not found in the table").build();

    public static final Set<Relationship> getDynamoDBrelationships = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(REL_SUCCESS, REL_FAILURE, REL_UNPROCESSED, REL_NOT_FOUND)));

    @Override
    public Set<Relationship> getRelationships() {
        return getDynamoDBrelationships;
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return properties;
    }

    @Override
    public List<ConfigVerificationResult> verify(final ProcessContext context, final ComponentLog verificationLogger, final Map<String, String> attributes) {
        final List<ConfigVerificationResult> results = new ArrayList<>(super.verify(context, verificationLogger, attributes));

        final String table = context.getProperty(TABLE).evaluateAttributeExpressions().getValue();
        final String jsonDocument = context.getProperty(JSON_DOCUMENT).evaluateAttributeExpressions().getValue();

        TableKeysAndAttributes tableKeysAndAttributes;

        try {
            tableKeysAndAttributes = getTableKeysAndAttributes(context, attributes);
            results.add(new ConfigVerificationResult.Builder()
                    .outcome(Outcome.SUCCESSFUL)
                    .verificationStepName("Configure DynamoDB BatchGetItems Request")
                    .explanation(String.format("Successfully configured BatchGetItems Request"))
                    .build());
        } catch (final IllegalArgumentException e) {
            verificationLogger.error("Failed to configured BatchGetItems Request", e);
            results.add(new ConfigVerificationResult.Builder()
                    .outcome(Outcome.FAILED)
                    .verificationStepName("Configure DynamoDB BatchGetItems Request")
                    .explanation(String.format("Failed to configured BatchGetItems Request: " + e.getMessage()))
                    .build());
            return results;
        }

        if (tableKeysAndAttributes.getPrimaryKeys() == null || tableKeysAndAttributes.getPrimaryKeys().isEmpty()) {
            results.add(new ConfigVerificationResult.Builder()
                    .outcome(Outcome.SKIPPED)
                    .verificationStepName("Get DynamoDB Items")
                    .explanation(String.format("Skipped getting DynamoDB items because no primary keys would be included in retrieval"))
                    .build());
        } else {
            try {
                final DynamoDB dynamoDB = getDynamoDB(getConfiguration(context).getClient());
                int totalCount = 0;
                int jsonDocumentCount = 0;

                BatchGetItemOutcome result = dynamoDB.batchGetItem(tableKeysAndAttributes);

                // Handle processed items and get the json document
                final List<Item> items = result.getTableItems().get(table);
                for (final Item item : items) {
                    totalCount++;
                    if (item.get(jsonDocument) != null) {
                        jsonDocumentCount++;
                    }
                }

                results.add(new ConfigVerificationResult.Builder()
                        .outcome(Outcome.SUCCESSFUL)
                        .verificationStepName("Get DynamoDB Items")
                        .explanation(String.format("Successfully retrieved %s items, including %s JSON documents, from DynamoDB", totalCount, jsonDocumentCount))
                        .build());

            } catch (final Exception e) {
                verificationLogger.error("Failed to retrieve items from DynamoDB", e);

                results.add(new ConfigVerificationResult.Builder()
                        .outcome(Outcome.FAILED)
                        .verificationStepName("Get DynamoDB Items")
                        .explanation(String.format("Failed to retrieve items from DynamoDB: %s", e.getMessage()))
                        .build());
            }
        }

        return results;
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) {
        final List<FlowFile> flowFiles = session.get(context.getProperty(BATCH_SIZE).evaluateAttributeExpressions().asInteger());
        if (flowFiles == null || flowFiles.size() == 0) {
            return;
        }

        final Map<ItemKeys,FlowFile> keysToFlowFileMap = getKeysToFlowFileMap(context, session, flowFiles);

        final TableKeysAndAttributes tableKeysAndAttributes;
        try {
            tableKeysAndAttributes = getTableKeysAndAttributes(context, flowFiles.stream()
                    .map(FlowFile::getAttributes).collect(Collectors.toList()).toArray(new Map[0]));
        } catch (final IllegalArgumentException e) {
            getLogger().error(e.getMessage(), e);
            return;
        }

        final String table = context.getProperty(TABLE).evaluateAttributeExpressions().getValue();
        final String hashKeyName = context.getProperty(HASH_KEY_NAME).evaluateAttributeExpressions().getValue();
        final String rangeKeyName = context.getProperty(RANGE_KEY_NAME).evaluateAttributeExpressions().getValue();
        final String jsonDocument = context.getProperty(JSON_DOCUMENT).evaluateAttributeExpressions().getValue();

        if (keysToFlowFileMap.isEmpty()) {
            return;
        }

        final DynamoDB dynamoDB = getDynamoDB();

        try {
            BatchGetItemOutcome result = dynamoDB.batchGetItem(tableKeysAndAttributes);

            // Handle processed items and get the json document
            final List<Item> items = result.getTableItems().get(table);
            for (final Item item : items) {
                final ItemKeys itemKeys = new ItemKeys(item.get(hashKeyName), item.get(rangeKeyName));
                FlowFile flowFile = keysToFlowFileMap.get(itemKeys);

                if (item.get(jsonDocument) != null) {
                    ByteArrayInputStream bais = new ByteArrayInputStream(item.getJSON(jsonDocument).getBytes());
                    flowFile = session.importFrom(bais, flowFile);
                }

                session.transfer(flowFile,REL_SUCCESS);
                keysToFlowFileMap.remove(itemKeys);
            }

            // Handle unprocessed keys
            final Map<String, KeysAndAttributes> unprocessedKeys = result.getUnprocessedKeys();
            if ( unprocessedKeys != null && unprocessedKeys.size() > 0) {
                final KeysAndAttributes keysAndAttributes = unprocessedKeys.get(table);
                final List<Map<String, AttributeValue>> keys = keysAndAttributes.getKeys();

                for (final Map<String,AttributeValue> unprocessedKey : keys) {
                    final Object hashKeyValue = getAttributeValue(context, HASH_KEY_VALUE_TYPE, unprocessedKey.get(hashKeyName));
                    final Object rangeKeyValue = getAttributeValue(context, RANGE_KEY_VALUE_TYPE, unprocessedKey.get(rangeKeyName));
                    sendUnprocessedToUnprocessedRelationship(session, keysToFlowFileMap, hashKeyValue, rangeKeyValue);
                }
            }

            // Handle any remaining items
            for (final ItemKeys key : keysToFlowFileMap.keySet()) {
                FlowFile flowFile = keysToFlowFileMap.get(key);
                flowFile = session.putAttribute(flowFile, DYNAMODB_KEY_ERROR_NOT_FOUND, DYNAMODB_KEY_ERROR_NOT_FOUND_MESSAGE + key.toString() );
                session.transfer(flowFile,REL_NOT_FOUND);
                keysToFlowFileMap.remove(key);
            }

        } catch(final AmazonServiceException exception) {
            getLogger().error("Could not process flowFiles due to service exception : " + exception.getMessage());
            List<FlowFile> failedFlowFiles = processServiceException(session, flowFiles, exception);
            session.transfer(failedFlowFiles, REL_FAILURE);
        } catch(final AmazonClientException exception) {
            getLogger().error("Could not process flowFiles due to client exception : " + exception.getMessage());
            List<FlowFile> failedFlowFiles = processClientException(session, flowFiles, exception);
            session.transfer(failedFlowFiles, REL_FAILURE);
        } catch(final Exception exception) {
            getLogger().error("Could not process flowFiles due to exception : " + exception.getMessage());
            List<FlowFile> failedFlowFiles = processException(session, flowFiles, exception);
            session.transfer(failedFlowFiles, REL_FAILURE);
        }
    }

    private Map<ItemKeys, FlowFile> getKeysToFlowFileMap(final ProcessContext context, final ProcessSession session, final List<FlowFile> flowFiles) {
        final Map<ItemKeys,FlowFile> keysToFlowFileMap = new HashMap<>();

        final String hashKeyName = context.getProperty(HASH_KEY_NAME).evaluateAttributeExpressions().getValue();
        final String rangeKeyName = context.getProperty(RANGE_KEY_NAME).evaluateAttributeExpressions().getValue();

        for (final FlowFile flowFile : flowFiles) {
            final Object hashKeyValue = getValue(context, HASH_KEY_VALUE_TYPE, HASH_KEY_VALUE, flowFile.getAttributes());
            final Object rangeKeyValue = getValue(context, RANGE_KEY_VALUE_TYPE, RANGE_KEY_VALUE, flowFile.getAttributes());

            if (!isHashKeyValueConsistent(hashKeyName, hashKeyValue, session, flowFile)) {
                continue;
            }

            if (!isRangeKeyValueConsistent(rangeKeyName, rangeKeyValue, session, flowFile)) {
                continue;
            }

            keysToFlowFileMap.put(new ItemKeys(hashKeyValue, rangeKeyValue), flowFile);
        }
        return keysToFlowFileMap;
    }

    private TableKeysAndAttributes getTableKeysAndAttributes(final ProcessContext context, final Map<String, String>... attributes) {
        final String table = context.getProperty(TABLE).evaluateAttributeExpressions().getValue();
        final TableKeysAndAttributes tableKeysAndAttributes = new TableKeysAndAttributes(table);

        final String hashKeyName = context.getProperty(HASH_KEY_NAME).evaluateAttributeExpressions().getValue();
        final String rangeKeyName = context.getProperty(RANGE_KEY_NAME).evaluateAttributeExpressions().getValue();

        for (final Map<String, String> attributeMap : attributes) {
            final Object hashKeyValue = getValue(context, HASH_KEY_VALUE_TYPE, HASH_KEY_VALUE, attributeMap);
            final Object rangeKeyValue = getValue(context, RANGE_KEY_VALUE_TYPE, RANGE_KEY_VALUE, attributeMap);

            validateHashKeyValue(hashKeyValue);
            validateRangeKeyValue(rangeKeyName, rangeKeyValue);

            if (rangeKeyValue == null || StringUtils.isBlank(rangeKeyValue.toString())) {
                tableKeysAndAttributes.addHashOnlyPrimaryKey(hashKeyName, hashKeyValue);
            } else {
                tableKeysAndAttributes.addHashAndRangePrimaryKey(hashKeyName, hashKeyValue, rangeKeyName, rangeKeyValue);
            }
        }
        return tableKeysAndAttributes;
    }

}
