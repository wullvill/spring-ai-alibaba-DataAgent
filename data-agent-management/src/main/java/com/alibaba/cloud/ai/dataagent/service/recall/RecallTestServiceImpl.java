/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.service.recall;

import com.alibaba.cloud.ai.dataagent.dto.recall.RecallResultItem;
import com.alibaba.cloud.ai.dataagent.dto.recall.RecallTestRequest;
import com.alibaba.cloud.ai.dataagent.dto.recall.RecallTestResponse;
import com.alibaba.cloud.ai.dataagent.service.vectorstore.AgentVectorStoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of recall test service.
 */
@Slf4j
@Service
public class RecallTestServiceImpl implements RecallTestService {

	private static final String SCORE_METADATA_KEY = "score";

	private final AgentVectorStoreService vectorStoreService;

	public RecallTestServiceImpl(AgentVectorStoreService vectorStoreService) {
		this.vectorStoreService = vectorStoreService;
	}

	@Override
	public RecallTestResponse testRecall(RecallTestRequest request) {
		log.info("Testing recall for agentId={}, query={}, vectorType={}", request.getAgentId(), request.getQuery(),
				request.getVectorType());

		List<Document> documents = vectorStoreService.getDocumentsForAgent(request.getAgentId(), request.getQuery(),
				request.getVectorType(), request.getTopK(), request.getThreshold());

		List<RecallResultItem> results = new ArrayList<>();
		int rank = 1;
		for (Document doc : documents) {
			double score = extractScore(doc);
			Map<String, Object> metadata = new HashMap<>(doc.getMetadata());

			RecallResultItem item = new RecallResultItem(rank++, score, doc.getText(), metadata);
			results.add(item);
		}

		log.info("Recall test completed: found {} results for agentId={}", results.size(), request.getAgentId());
		return new RecallTestResponse(request.getQuery(), results.size(), results);
	}

	private double extractScore(Document document) {
		Map<String, Object> metadata = document.getMetadata();
		if (metadata != null && metadata.containsKey(SCORE_METADATA_KEY)) {
			Object score = metadata.get(SCORE_METADATA_KEY);
			if (score instanceof Number) {
				return ((Number) score).doubleValue();
			}
		}
		return 0.0;
	}

}
