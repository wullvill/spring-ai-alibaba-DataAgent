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

import com.alibaba.cloud.ai.dataagent.dto.recall.RecallTestRequest;
import com.alibaba.cloud.ai.dataagent.dto.recall.RecallTestResponse;

/**
 * Service interface for recall testing functionality.
 */
public interface RecallTestService {

	/**
	 * Test recall results for a given query against an agent's knowledge base.
	 * @param request the recall test request containing query and parameters
	 * @return recall test response with ranked results
	 */
	RecallTestResponse testRecall(RecallTestRequest request);

}
