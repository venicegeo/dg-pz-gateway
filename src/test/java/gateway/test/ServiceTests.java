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
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import gateway.controller.ServiceController;
import gateway.controller.util.GatewayUtil;

import java.security.Principal;
import java.util.ArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import javax.management.remote.JMXPrincipal;

import model.job.metadata.ResourceMetadata;
import model.response.ErrorResponse;
import model.response.Pagination;
import model.response.PiazzaResponse;
import model.response.ServiceIdResponse;
import model.response.ServiceListResponse;
import model.response.ServiceResponse;
import model.service.metadata.Service;

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
 * Tests the Piazza Service Controller Rest Controller
 * 
 * @author Patrick.Doody
 *
 */
public class ServiceTests {
	@Mock
	private PiazzaLogger logger;
	@Mock
	private UUIDFactory uuidFactory;
	@Mock
	private GatewayUtil gatewayUtil;
	@Mock
	private RestTemplate restTemplate;
	@InjectMocks
	private ServiceController serviceController;
	@Mock
	private Producer<String, String> producer;

	private Principal user;
	private Service mockService;
	private ErrorResponse mockError;

	/**
	 * Initialize mock objects.
	 */
	@Before
	public void setup() {
		MockitoAnnotations.initMocks(this);
		MockitoAnnotations.initMocks(gatewayUtil);

		// Mock a common error we can use to test
		mockError = new ErrorResponse("Service", "Error!", "Test");

		// Mock a Service to use
		mockService = new Service();
		mockService.setServiceId("123456");
		mockService.setUrl("service.com");
		mockService.setResourceMetadata(new ResourceMetadata());
		mockService.getResourceMetadata().setName("Test");

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
	 * Test POST /service endpoint
	 */
	@Test
	public void testRegister() {
		// Mock
		Service service = new Service();
		service.setServiceId("123456");
		ServiceIdResponse mockResponse = new ServiceIdResponse(service.getServiceId());
		when(restTemplate.postForObject(anyString(), any(), eq(PiazzaResponse.class))).thenReturn(mockResponse);

		// Test
		ResponseEntity<PiazzaResponse> entity = serviceController.registerService(service, user);
		ServiceIdResponse response = (ServiceIdResponse) entity.getBody();

		// Verify
		assertTrue(entity.getStatusCode().equals(HttpStatus.OK));
		assertTrue(response.serviceId.equals("123456"));

		// Test Exception
		when(restTemplate.postForObject(anyString(), any(), eq(PiazzaResponse.class))).thenThrow(
				new RestClientException("rest error"));
		entity = serviceController.registerService(service, user);
		assertTrue(entity.getStatusCode().equals(HttpStatus.INTERNAL_SERVER_ERROR));
		assertTrue(entity.getBody() instanceof ErrorResponse);
		assertTrue(((ErrorResponse) entity.getBody()).message.contains("rest error"));
	}

	/**
	 * Test GET /service/{serviceId}
	 */
	@Test
	public void testGetMetadata() {
		// Mock
		ServiceResponse mockResponse = new ServiceResponse(mockService);
		when(restTemplate.getForObject(anyString(), eq(PiazzaResponse.class))).thenReturn(mockResponse);

		// Test
		ResponseEntity<PiazzaResponse> entity = serviceController.getService("123456", user);
		ServiceResponse response = (ServiceResponse) entity.getBody();

		// Verify
		assertTrue(entity.getStatusCode().equals(HttpStatus.OK));
		assertTrue(response.service.getServiceId().equalsIgnoreCase("123456"));
		assertTrue(response.service.getResourceMetadata().getName().equalsIgnoreCase("Test"));

		// Test Exception
		when(restTemplate.getForObject(anyString(), eq(PiazzaResponse.class))).thenThrow(
				new RestClientException("No Service"));
		entity = serviceController.getService("123456", user);
		assertTrue(entity.getStatusCode().equals(HttpStatus.INTERNAL_SERVER_ERROR));
		assertTrue(entity.getBody() instanceof ErrorResponse);
		assertTrue(((ErrorResponse) entity.getBody()).message.contains("No Service"));
	}

	/**
	 * Test DELETE /service/{serviceId}
	 */
	@Test
	public void testDelete() {
		// Mock
		Mockito.doNothing().when(restTemplate).delete(anyString());

		// Test
		ResponseEntity<?> entity = serviceController.deleteService("123456", false, user);
		ServiceIdResponse response = (ServiceIdResponse) entity.getBody();

		// Verify
		assertTrue(entity.getStatusCode().equals(HttpStatus.OK));
		assertTrue(response.serviceId.equalsIgnoreCase("123456"));

		// Test Exception
		Mockito.doThrow(new RestClientException("Delete Error")).when(restTemplate).delete(anyString());
		entity = serviceController.deleteService("123456", false, user);
		assertTrue(entity.getStatusCode().equals(HttpStatus.INTERNAL_SERVER_ERROR));
		assertTrue(entity.getBody() instanceof ErrorResponse);
		assertTrue(((ErrorResponse) entity.getBody()).message.contains("Delete Error"));
	}

	/**
	 * Test PUT /service/{serviceId}
	 */
	@Test
	public void testUpdateMetadata() {
		// Mock
		Mockito.doNothing().when(restTemplate).put(anyString(), anyObject());

		// Test
		ResponseEntity<PiazzaResponse> entity = serviceController.updateService("123456", mockService, user);

		// Verify
		assertTrue(entity == null);

		// Test Exception
		Mockito.doThrow(new RestClientException("Update Error")).when(restTemplate).put(anyString(), anyObject());
		entity = serviceController.updateService("123456", mockService, user);
		assertTrue(entity.getStatusCode().equals(HttpStatus.INTERNAL_SERVER_ERROR));
		assertTrue(entity.getBody() instanceof ErrorResponse);
		assertTrue(((ErrorResponse) entity.getBody()).message.contains("Update Error"));
	}

	/**
	 * Test GET /service
	 */
	@Test
	public void testGetServices() {
		// Mock
		ServiceListResponse mockResponse = new ServiceListResponse();
		mockResponse.data = new ArrayList<Service>();
		mockResponse.getData().add(mockService);
		mockResponse.pagination = new Pagination(1, 0, 10);
		when(restTemplate.getForObject(anyString(), eq(PiazzaResponse.class))).thenReturn(mockResponse);

		// Test
		ResponseEntity<PiazzaResponse> entity = serviceController.getServices(null, 0, 10, null, user);
		PiazzaResponse response = entity.getBody();

		// Verify
		assertTrue(response instanceof ServiceListResponse);
		ServiceListResponse serviceList = (ServiceListResponse) response;
		assertTrue(serviceList.getData().size() == 1);
		assertTrue(serviceList.getPagination().getCount() == 1);
		assertTrue(entity.getStatusCode().equals(HttpStatus.OK));

		// Test Exception
		when(restTemplate.getForObject(anyString(), eq(PiazzaResponse.class))).thenReturn(mockError);
		entity = serviceController.getServices(null, 0, 10, null, user);
		response = entity.getBody();
		assertTrue(response instanceof ErrorResponse);
		assertTrue(entity.getStatusCode().equals(HttpStatus.INTERNAL_SERVER_ERROR));
	}

	/**
	 * Test service/query
	 */
	@Test
	public void testQuery() {
		// Mock
		ServiceListResponse mockResponse = new ServiceListResponse();
		mockResponse.data = new ArrayList<Service>();
		mockResponse.getData().add(mockService);
		mockResponse.pagination = new Pagination(1, 0, 10);
		when(restTemplate.postForObject(anyString(), any(), eq(ServiceListResponse.class))).thenReturn(mockResponse);

		// Test
		ResponseEntity<PiazzaResponse> entity = serviceController.searchServices(null, user);
		ServiceListResponse response = (ServiceListResponse) entity.getBody();

		// Verify
		assertTrue(entity.getStatusCode().equals(HttpStatus.OK));
		assertTrue(response.getData().get(0).getServiceId().equalsIgnoreCase(mockService.getServiceId()));
		assertTrue(response.getPagination().getCount().equals(1));

		// Test Exception
		when(restTemplate.postForObject(anyString(), any(), eq(ServiceListResponse.class))).thenThrow(
				new RestClientException("Error"));
		entity = serviceController.searchServices(null, user);
		assertTrue(entity.getStatusCode().equals(HttpStatus.INTERNAL_SERVER_ERROR));
		assertTrue(entity.getBody() instanceof ErrorResponse);
	}
}