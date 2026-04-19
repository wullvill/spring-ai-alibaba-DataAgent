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
package com.alibaba.cloud.ai.dataagent.controller;

import com.alibaba.cloud.ai.dataagent.dto.recall.RecallTestRequest;
import com.alibaba.cloud.ai.dataagent.dto.recall.RecallTestResponse;
import com.alibaba.cloud.ai.dataagent.exception.InvalidInputException;
import com.alibaba.cloud.ai.dataagent.service.recall.RecallTestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * Controller for recall test functionality.
 */
@Slf4j
@RestController
@RequestMapping("/api/recall")
public class RecallTestController {

	private final RecallTestService recallTestService;

	public RecallTestController(RecallTestService recallTestService) {
		this.recallTestService = recallTestService;
	}

	/**
	 * Test recall results for a given query.
	 */
	@PostMapping("/test")
	public RecallTestResponse testRecall(@RequestBody RecallTestRequest request) {
		validateRequest(request);
		return recallTestService.testRecall(request);
	}

	private void validateRequest(RecallTestRequest request) {
		if (request.getAgentId() == null || request.getAgentId().isBlank()) {
			throw new InvalidInputException("Agent ID is required");
		}
		if (request.getQuery() == null || request.getQuery().isBlank()) {
			throw new InvalidInputException("Query is required");
		}
	}

}
