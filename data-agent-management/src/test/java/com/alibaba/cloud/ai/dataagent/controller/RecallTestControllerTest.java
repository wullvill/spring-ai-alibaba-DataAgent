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

import com.alibaba.cloud.ai.dataagent.dto.recall.RecallResultItem;
import com.alibaba.cloud.ai.dataagent.dto.recall.RecallTestRequest;
import com.alibaba.cloud.ai.dataagent.dto.recall.RecallTestResponse;
import com.alibaba.cloud.ai.dataagent.exception.InvalidInputException;
import com.alibaba.cloud.ai.dataagent.service.recall.RecallTestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecallTestControllerTest {

	@Mock
	private RecallTestService recallTestService;

	private RecallTestController controller;

	@BeforeEach
	void setUp() {
		controller = new RecallTestController(recallTestService);
	}

	@Test
	@DisplayName("testRecall returns response when valid request")
	void testRecall_validRequest_returnsResponse() {
		RecallTestRequest request = RecallTestRequest.builder()
			.agentId("5")
			.query("test query")
			.topK(5)
			.threshold(0.5)
			.build();

		RecallTestResponse expectedResponse = new RecallTestResponse("test query", 2,
				List.of(new RecallResultItem(1, 0.95, "content1", Map.of("source", "doc1")),
						new RecallResultItem(2, 0.85, "content2", Map.of("source", "doc2"))));

		when(recallTestService.testRecall(any(RecallTestRequest.class))).thenReturn(expectedResponse);

		RecallTestResponse response = controller.testRecall(request);

		assertThat(response.query()).isEqualTo("test query");
		assertThat(response.total()).isEqualTo(2);
		assertThat(response.results()).hasSize(2);
		verify(recallTestService).testRecall(request);
	}

	@Test
	@DisplayName("testRecall throws exception when agentId is null")
	void testRecall_nullAgentId_throwsException() {
		RecallTestRequest request = RecallTestRequest.builder().query("test query").build();

		assertThatThrownBy(() -> controller.testRecall(request))
			.isInstanceOf(InvalidInputException.class)
			.hasMessageContaining("Agent ID is required");
	}

	@Test
	@DisplayName("testRecall throws exception when agentId is blank")
	void testRecall_blankAgentId_throwsException() {
		RecallTestRequest request = RecallTestRequest.builder().agentId("  ").query("test query").build();

		assertThatThrownBy(() -> controller.testRecall(request))
			.isInstanceOf(InvalidInputException.class)
			.hasMessageContaining("Agent ID is required");
	}

	@Test
	@DisplayName("testRecall throws exception when query is null")
	void testRecall_nullQuery_throwsException() {
		RecallTestRequest request = RecallTestRequest.builder().agentId("agent-123").build();

		assertThatThrownBy(() -> controller.testRecall(request))
			.isInstanceOf(InvalidInputException.class)
			.hasMessageContaining("Query is required");
	}

	@Test
	@DisplayName("testRecall throws exception when query is blank")
	void testRecall_blankQuery_throwsException() {
		RecallTestRequest request = RecallTestRequest.builder().agentId("agent-123").query("   ").build();

		assertThatThrownBy(() -> controller.testRecall(request))
			.isInstanceOf(InvalidInputException.class)
			.hasMessageContaining("Query is required");
	}

	@Test
	@DisplayName("testRecall accepts request with default vectorType and topK")
	void testRecall_withDefaults_accepted() {
		RecallTestRequest request = RecallTestRequest.builder()
			.agentId("agent-123")
			.query("test query")
			.vectorType("agentKnowledge")
			.topK(8)
			.threshold(0.4)
			.build();

		RecallTestResponse expectedResponse = new RecallTestResponse("test query", 0, List.of());
		when(recallTestService.testRecall(any(RecallTestRequest.class))).thenReturn(expectedResponse);

		RecallTestResponse response = controller.testRecall(request);

		assertThat(response.results()).isEmpty();
		verify(recallTestService).testRecall(request);
	}

	@Test
	@DisplayName("testRecall passes custom vectorType to service")
	void testRecall_customVectorType_passedToService() {
		RecallTestRequest request = RecallTestRequest.builder()
			.agentId("agent-123")
			.query("test query")
			.vectorType("customVector")
			.build();

		RecallTestResponse expectedResponse = new RecallTestResponse("test query", 1,
				List.of(new RecallResultItem(1, 0.9, "content", Map.of())));
		when(recallTestService.testRecall(any(RecallTestRequest.class))).thenReturn(expectedResponse);

		controller.testRecall(request);

		verify(recallTestService).testRecall(request);
	}

}

