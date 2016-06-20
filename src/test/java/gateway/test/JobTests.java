/**
 * Copyright 2016, RadiantBlue Technologies, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/
package gateway.test;

import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import gateway.controller.JobController;
import gateway.controller.util.GatewayUtil;

import java.security.Principal;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import javax.management.remote.JMXPrincipal;

import model.job.Job;
import model.job.JobProgress;
import model.job.type.ExecuteServiceJob;
import model.job.type.RepeatJob;
import model.response.ErrorResponse;
import model.response.JobStatusResponse;
import model.response.PiazzaResponse;
import model.service.metadata.ExecuteServiceData;
import model.status.StatusUpdate;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import util.PiazzaLogger;
import util.UUIDFactory;

/**
 * Tests the Job Controller
 * 
 * @author Patrick.Doody
 *
 */
public class JobTests {
	@Mock
	private PiazzaLogger logger;
	@Mock
	private UUIDFactory uuidFactory;
	@Mock
	private GatewayUtil gatewayUtil;
	@Mock
	private RestTemplate restTemplate;
	@InjectMocks
	private JobController jobController;
	@Mock
	private Producer<String, String> producer;

	private Principal user;
	private Job mockJob;
	private ErrorResponse mockError;

	/**
	 * Initialize mock objects.
	 */
	@Before
	public void setup() {
		MockitoAnnotations.initMocks(this);
		MockitoAnnotations.initMocks(gatewayUtil);

		// Mock a common error we can use to test
		mockError = new ErrorResponse("123456", "Job Not Found", "Gateway");

		// Mock a Job
		mockJob = new Job();
		mockJob.setJobId("123456");
		mockJob.jobType = new RepeatJob("654321");
		mockJob.progress = new JobProgress(50);
		mockJob.submitterUserName = "Test User 2";
		mockJob.status = StatusUpdate.STATUS_RUNNING;

		// Mock a user
		user = new JMXPrincipal("Test User");

		// Mock the Kafka response that Producers will send. This will always
		// return a Future that completes immediately and simply returns true.
		when(producer.send(isA(ProducerRecord.class))).thenAnswer(new Answer<Future<Boolean>>() {
			@Override
			public Future<Boolean> answer(InvocationOnMock invocation) throws Throwable {
				Future<Boolean> future = mock(FutureTask.class);
				when(future.isDone()).thenReturn(true);
				when(future.get()).thenReturn(true);
				return future;
			}
		});
	}

	/**
	 * Test GET /job/{jobId}
	 */
	@Test
	public void testGetStatus() {
		// Mock
		JobStatusResponse mockResponse = new JobStatusResponse(mockJob);
		when(restTemplate.getForObject(anyString(), eq(PiazzaResponse.class))).thenReturn(mockResponse);

		// Test
		ResponseEntity<PiazzaResponse> entity = jobController.getJobStatus("123456", user);
		JobStatusResponse response = (JobStatusResponse) entity.getBody();

		// Verify
		assertTrue(entity.getStatusCode().equals(HttpStatus.OK));
		assertTrue(response.jobId.equals("123456"));
		assertTrue(response.status.equalsIgnoreCase(StatusUpdate.STATUS_RUNNING));
		assertTrue(response.progress.getPercentComplete().equals(50));
		assertTrue(response.submittedBy.equals("Test User 2"));

		// Test Exception
		when(restTemplate.getForObject(anyString(), eq(PiazzaResponse.class))).thenReturn(mockError);
		entity = jobController.getJobStatus("123456", user);
		assertTrue(entity.getBody() instanceof ErrorResponse);
		assertTrue(entity.getStatusCode().equals(HttpStatus.INTERNAL_SERVER_ERROR));
		ErrorResponse error = (ErrorResponse) entity.getBody();
		assertTrue(error.message.contains("Job Not Found"));
	}

	/**
	 * Test DELETE /job/{jobId}
	 */
	@Test
	public void testAbort() {
		// Mock
		PiazzaResponse mockResponse = new PiazzaResponse();
		ResponseEntity<PiazzaResponse> mockEntity = new ResponseEntity<PiazzaResponse>(mockResponse, HttpStatus.OK);
		when(restTemplate.postForEntity(anyString(), any(), eq(PiazzaResponse.class))).thenReturn(mockEntity);

		// Test
		ResponseEntity<PiazzaResponse> entity = jobController.abortJob("123456", "Not Needed", user);

		// Verify
		assertTrue(entity.getStatusCode().equals(HttpStatus.OK));

		// Test Exception
		when(restTemplate.postForEntity(anyString(), any(), eq(PiazzaResponse.class))).thenThrow(
				new RestClientException("Could Not Abort"));
		entity = jobController.abortJob("123456", "Not Needed", user);
		assertTrue(entity.getStatusCode().equals(HttpStatus.INTERNAL_SERVER_ERROR));
		assertTrue(entity.getBody() instanceof ErrorResponse);
		ErrorResponse error = (ErrorResponse) entity.getBody();
		assertTrue(error.message.contains("Could Not Abort"));
	}

	/**
	 * Test PUT /job/{jobId}
	 */
	@Test
	public void testRepeat() {
		// Mock
		PiazzaResponse mockResponse = new PiazzaResponse();
		ResponseEntity<PiazzaResponse> mockEntity = new ResponseEntity<PiazzaResponse>(mockResponse, HttpStatus.OK);
		when(restTemplate.postForEntity(anyString(), any(), eq(PiazzaResponse.class))).thenReturn(mockEntity);

		// Test
		ResponseEntity<PiazzaResponse> entity = jobController.repeatJob("123456", user);

		// Verify
		assertTrue(entity.getStatusCode().equals(HttpStatus.OK));

		// Test Exception
		when(restTemplate.postForEntity(anyString(), any(), eq(PiazzaResponse.class))).thenThrow(
				new RestClientException("Could Not Repeat"));
		entity = jobController.repeatJob("123456", user);
		assertTrue(entity.getStatusCode().equals(HttpStatus.INTERNAL_SERVER_ERROR));
		assertTrue(entity.getBody() instanceof ErrorResponse);
		ErrorResponse error = (ErrorResponse) entity.getBody();
		assertTrue(error.message.contains("Could Not Repeat"));
	}

	/**
	 * Test POST /v2/job
	 */
	@Test
	public void testExecute() throws Exception {
		// Mock
		ExecuteServiceJob executeJob = new ExecuteServiceJob("123456");
		executeJob.data = new ExecuteServiceData();
		executeJob.data.setServiceId("654321");

		// Test
		ResponseEntity<PiazzaResponse> entity = jobController.executeService(executeJob, user);

		// Verify
		assertTrue(entity.getStatusCode().equals(HttpStatus.OK));

		// Test Exception
		Mockito.doThrow(new Exception("Kafka Broke")).when(gatewayUtil).sendKafkaMessage(any());
		entity = jobController.executeService(executeJob, user);
		assertTrue(entity.getStatusCode().equals(HttpStatus.INTERNAL_SERVER_ERROR));
		assertTrue(entity.getBody() instanceof ErrorResponse);
		ErrorResponse error = (ErrorResponse) entity.getBody();
		assertTrue(error.message.contains("Kafka Broke"));
	}
}