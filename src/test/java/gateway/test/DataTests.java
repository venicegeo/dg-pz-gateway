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
import gateway.controller.DataController;
import gateway.controller.util.GatewayUtil;

import java.security.Principal;
import java.util.ArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import javax.management.remote.JMXPrincipal;

import model.data.DataResource;
import model.data.type.GeoJsonDataType;
import model.data.type.TextDataType;
import model.job.metadata.ResourceMetadata;
import model.job.type.IngestJob;
import model.response.DataResourceListResponse;
import model.response.DataResourceResponse;
import model.response.ErrorResponse;
import model.response.Pagination;
import model.response.PiazzaResponse;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import util.PiazzaLogger;
import util.UUIDFactory;

import com.amazonaws.services.s3.AmazonS3;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests the Data Controller, and various Data Access/Load Jobs.
 * 
 * @author Patrick.Doody
 * 
 */
public class DataTests {
	@Mock
	private PiazzaLogger logger;
	@Mock
	private UUIDFactory uuidFactory;
	@Mock
	private GatewayUtil gatewayUtil;
	@Mock
	private RestTemplate restTemplate;
	@Mock
	private AmazonS3 s3Client;
	@InjectMocks
	private DataController dataController;
	@Mock
	private Producer<String, String> producer;

	private Principal user;
	private DataResource mockData;
	private ErrorResponse mockError;

	/**
	 * Initialize mock objects.
	 */
	@Before
	public void setup() {
		MockitoAnnotations.initMocks(this);
		MockitoAnnotations.initMocks(gatewayUtil);

		// Mock a common error we can use to test
		mockError = new ErrorResponse("JobID", "Error!", "Test");

		// Mock some Data that we can use in our test cases.
		mockData = new DataResource();
		mockData.dataId = "DataID";
		mockData.dataType = new TextDataType();
		((TextDataType) mockData.dataType).content = "MockData";
		mockData.metadata = new ResourceMetadata();
		mockData.metadata.setName("Test Data");

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
	 * Test GET /data endpoint
	 */
	@Test
	public void testGetData() {
		// When the Gateway asks the Dispatcher for a List of Data, Mock that
		// response here.
		DataResourceListResponse mockResponse = new DataResourceListResponse();
		mockResponse.data = new ArrayList<DataResource>();
		mockResponse.getData().add(mockData);
		mockResponse.pagination = new Pagination(1, 0, 10);
		when(restTemplate.getForObject(anyString(), eq(PiazzaResponse.class))).thenReturn(mockResponse);

		// Get the data
		ResponseEntity<PiazzaResponse> entity = dataController.getData(null, 0, 10, null, user);
		PiazzaResponse response = entity.getBody();

		// Verify the results
		assertTrue(response instanceof DataResourceListResponse);
		DataResourceListResponse dataList = (DataResourceListResponse) response;
		assertTrue(dataList.getData().size() == 1);
		assertTrue(dataList.getPagination().getCount() == 1);
		assertTrue(entity.getStatusCode().equals(HttpStatus.OK));

		// Mock an Exception being thrown and handled.
		when(restTemplate.getForObject(anyString(), eq(PiazzaResponse.class))).thenReturn(mockError);

		// Get the data
		entity = dataController.getData(null, 0, 10, null, user);
		response = entity.getBody();

		// Verify that a proper exception was thrown.
		assertTrue(response instanceof ErrorResponse);
		assertTrue(entity.getStatusCode().equals(HttpStatus.INTERNAL_SERVER_ERROR));
	}

	/**
	 * Test POST /data endpoint
	 */
	@Test
	public void testAddData() throws Exception {
		// Mock an Ingest Job request, containing some sample data we want to
		// ingest.
		IngestJob mockJob = new IngestJob();
		mockJob.data = mockData;
		mockJob.host = false;

		// Generate a UUID that we can reproduce.
		when(gatewayUtil.getUuid()).thenReturn("123456");

		// Submit a mock request
		ResponseEntity<PiazzaResponse> entity = dataController.ingestData(mockJob, user);
		PiazzaResponse response = entity.getBody();

		// Verify the results. If the mock Kafka message is sent, then this is
		// considered a success.
		assertTrue(response instanceof PiazzaResponse == true);
		assertTrue(response instanceof ErrorResponse == false);
		assertTrue(response.jobId.equalsIgnoreCase("123456"));
		assertTrue(entity.getStatusCode().equals(HttpStatus.OK));

		// Test an Exception
		when(gatewayUtil.getUuid()).thenThrow(new Exception());
		entity = dataController.ingestData(mockJob, user);
		assertTrue(entity.getStatusCode().equals(HttpStatus.INTERNAL_SERVER_ERROR));
	}

	/**
	 * Test POST /data/file endpoint
	 * 
	 * @throws Exception
	 */
	@Test
	public void testAddFile() throws Exception {
		// Mock an Ingest Job request, containing some sample data we want to
		// ingest. Also mock a file.
		IngestJob mockJob = new IngestJob();
		mockJob.data = mockData;
		mockJob.host = false; // This will cause a failure initially
		MockMultipartFile file = new MockMultipartFile("test.tif", "Content".getBytes());

		// Generate a UUID that we can reproduce.
		when(gatewayUtil.getUuid()).thenReturn("123456");

		// Test the request
		ResponseEntity<PiazzaResponse> entity = dataController.ingestDataFile(
				new ObjectMapper().writeValueAsString(mockJob), file, user);
		PiazzaResponse response = entity.getBody();

		// Verify the results. This request should fail since the host flag is
		// set to false.
		assertTrue(response instanceof ErrorResponse == true);

		mockJob.host = true;
		// Resubmit the Job. Now it should fail because it is a TextResource.
		entity = dataController.ingestDataFile(new ObjectMapper().writeValueAsString(mockJob), file, user);
		response = entity.getBody();

		assertTrue(response instanceof ErrorResponse == true);

		// Change to a File that should succeed.
		mockJob.data = new DataResource();
		mockJob.data.dataType = new GeoJsonDataType();

		// Resubmit the Job. It should now succeed with the message successfully
		// being sent to Kafka.
		entity = dataController.ingestDataFile(new ObjectMapper().writeValueAsString(mockJob), file, user);
		response = entity.getBody();

		assertTrue(response instanceof ErrorResponse == false);
		assertTrue(response.jobId.equalsIgnoreCase("123456"));
		assertTrue(entity.getStatusCode().equals(HttpStatus.OK));
	}

	/**
	 * Test GET /data/{dataId}
	 */
	@Test
	public void testGetDataItem() {
		// Mock the Response
		DataResourceResponse mockResponse = new DataResourceResponse(mockData);
		when(restTemplate.getForObject(anyString(), eq(PiazzaResponse.class))).thenReturn(mockResponse);

		// Test
		ResponseEntity<PiazzaResponse> entity = dataController.getMetadata("123456", user);
		PiazzaResponse response = entity.getBody();

		// Verify
		assertTrue(response instanceof ErrorResponse == false);
		assertTrue(((DataResourceResponse) response).data.getDataId().equalsIgnoreCase(mockData.getDataId()));
		assertTrue(entity.getStatusCode().equals(HttpStatus.OK));

		// Test an Exception
		when(restTemplate.getForObject(anyString(), eq(PiazzaResponse.class))).thenReturn(mockError);
		entity = dataController.getMetadata("123456", user);
		response = entity.getBody();
		assertTrue(response instanceof ErrorResponse);
		assertTrue(entity.getStatusCode().equals(HttpStatus.INTERNAL_SERVER_ERROR));
	}

	/**
	 * Test DELETE /data/{dataId}
	 */
	@Test
	public void testDeleteData() {
		// Mock the Response
		PiazzaResponse mockResponse = new PiazzaResponse();
		ResponseEntity<PiazzaResponse> mockEntity = new ResponseEntity<PiazzaResponse>(mockResponse, HttpStatus.OK);
		when(restTemplate.exchange(anyString(), any(), any(), eq(PiazzaResponse.class))).thenReturn(mockEntity);

		// Test
		ResponseEntity<PiazzaResponse> entity = dataController.deleteData("123456", user);
		PiazzaResponse response = entity.getBody();

		// Verify
		assertTrue(response instanceof ErrorResponse == false);
		assertTrue(entity.getStatusCode().equals(HttpStatus.OK));

		// Test an Exception
		mockEntity = new ResponseEntity<PiazzaResponse>(mockError, HttpStatus.INTERNAL_SERVER_ERROR);
		when(restTemplate.exchange(anyString(), any(), any(), eq(PiazzaResponse.class))).thenReturn(mockEntity);
		entity = dataController.deleteData("123456", user);
		response = entity.getBody();
		assertTrue(response instanceof ErrorResponse);
		assertTrue(entity.getStatusCode().equals(HttpStatus.INTERNAL_SERVER_ERROR));
	}

	/**
	 * Test POST /data/{dataId}
	 */
	@Test
	public void testUpdateData() {
		// Mock
		PiazzaResponse mockResponse = new PiazzaResponse();
		when(restTemplate.postForObject(anyString(), any(), eq(PiazzaResponse.class))).thenReturn(mockResponse);

		// Test
		ResponseEntity<PiazzaResponse> entity = dataController.updateMetadata("123456", mockData.getMetadata(), user);

		// Verify
		assertTrue(entity == null);

		// Test an Exception
		when(restTemplate.postForObject(anyString(), any(), eq(PiazzaResponse.class))).thenReturn(mockError);
		entity = dataController.updateMetadata("123456", mockData.getMetadata(), user);
		assertTrue(entity.getStatusCode().equals(HttpStatus.INTERNAL_SERVER_ERROR));
		assertTrue(entity.getBody() instanceof ErrorResponse);
	}

	/**
	 * Test POST /data/query
	 */
	@Test
	public void testQueryData() {
		// Mock
		DataResourceListResponse mockResponse = new DataResourceListResponse();
		mockResponse.data = new ArrayList<DataResource>();
		mockResponse.getData().add(mockData);
		mockResponse.pagination = new Pagination(1, 0, 10);
		when(restTemplate.postForObject(anyString(), any(), eq(DataResourceListResponse.class))).thenReturn(
				mockResponse);

		// Test
		ResponseEntity<PiazzaResponse> entity = dataController.searchData(null, user);
		DataResourceListResponse response = (DataResourceListResponse) entity.getBody();

		// Verify
		assertTrue(entity.getStatusCode().equals(HttpStatus.OK));
		assertTrue(response.getData().get(0).getDataId().equalsIgnoreCase(mockData.getDataId()));
		assertTrue(response.getPagination().getCount().equals(1));

		// Test an Exception
		when(restTemplate.postForObject(anyString(), any(), eq(DataResourceListResponse.class))).thenThrow(
				new RestClientException(""));
		entity = dataController.searchData(null, user);
		assertTrue(entity.getStatusCode().equals(HttpStatus.INTERNAL_SERVER_ERROR));
		assertTrue(entity.getBody() instanceof ErrorResponse);
	}

	/**
	 * Test GET /file/{dataId}
	 */
	@Test
	public void testDownload() throws Exception {
		// Mock
		ResponseEntity<byte[]> mockResponse = new ResponseEntity<byte[]>("Content".getBytes(), HttpStatus.OK);
		when(restTemplate.getForEntity(anyString(), eq(byte[].class))).thenReturn(mockResponse);

		// Test
		ResponseEntity<byte[]> entity = dataController.getFile("123456", "test.txt", user);

		// Verify
		assertTrue(entity.getStatusCode().equals(HttpStatus.OK));
		System.out.println(entity.getBody().toString());

		// Test an Exception
		when(restTemplate.getForEntity(anyString(), eq(byte[].class))).thenThrow(new RestClientException(""));
		try {
			entity = dataController.getFile("123456", "test.txt", user);
		} catch (Exception exception) {
			assertTrue(exception.getMessage().contains("Error downloading file"));
		}

	}
}