package main.java.gateway.controller;

import java.util.UUID;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import main.java.gateway.auth.AuthConnector;
import messaging.job.JobMessageFactory;
import messaging.job.KafkaClientFactory;
import model.job.type.GetJob;
import model.request.PiazzaJobRequest;
import model.response.ErrorResponse;
import model.response.PiazzaResponse;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;

@RestController
public class GatewayController {
	/**
	 * The Kafka Producer that will send messages from this controller to the
	 * Dispatcher. Initialized upon Controller startup.
	 */
	private Producer<String, String> producer;
	@Value("${kafka.host}")
	private String KAFKA_HOST;
	@Value("${kafka.port}")
	private String KAFKA_PORT;
	@Value("${dispatcher.host}")
	private String DISPATCHER_HOST;
	@Value("${dispatcher.port}")
	private String DISPATCHER_PORT;

	/**
	 * Initializing the Kafka Producer on Controller startup.
	 */
	@PostConstruct
	public void init() {
		producer = KafkaClientFactory.getProducer(KAFKA_HOST, KAFKA_PORT);
	}

	@PreDestroy
	public void cleanup() {
		producer.close();
	}

	/**
	 * Executes a Piazza Job
	 * 
	 * @param json
	 *            The JSON Payload
	 * @return Response object.
	 */
	@RequestMapping(value = "/job", method = RequestMethod.POST)
	public PiazzaResponse job(@RequestBody String json) {

		// Deserialize the incoming JSON to Request Model objects
		PiazzaJobRequest request;
		try {
			request = JobMessageFactory.parseRequestJson(json);
		} catch (Exception exception) {
			return new ErrorResponse(null, "Error Parsing JSON: " + exception.getMessage(), "Gateway");
		}

		// Authenticate and Authorize the request
		try {
			AuthConnector.verifyAuth(request);
		} catch (SecurityException securityEx) {
			return new ErrorResponse(null, "Authentication Error", "Gateway");
		}

		// Determine if this Job is processed via synchronous REST, or via Kafka
		// message queues.
		if (request.jobType instanceof GetJob) {
			// Ensure the Job ID is populated.
			GetJob getJob = (GetJob) request.jobType;
			if (getJob.getJobId() == null || getJob.getJobId().isEmpty()) {
				return new ErrorResponse(null, "Missing JobID Property.", "Gateway");
			}
			// REST GET request to Dispatcher to fetch the status of the Job ID.
			RestTemplate restTemplate = new RestTemplate();
			try {
				PiazzaResponse dispatcherResponse = restTemplate.getForObject(
						String.format("http://%s:%s/job/%s", DISPATCHER_HOST, DISPATCHER_PORT, getJob.getJobId()),
						PiazzaResponse.class);
				return dispatcherResponse;
			} catch (RestClientException exception) {
				return new ErrorResponse(null, "Error connecting to Dispatcher service: " + exception.getMessage(),
						"Gateway");
			}
		} else {
			// Create a GUID for this new Job.
			String jobId = UUID.randomUUID().toString();
			// Create the Kafka Message for an incoming Job to be created.
			final ProducerRecord<String, String> message;
			try {
				message = JobMessageFactory.getRequestJobMessage(request, jobId);
			} catch (JsonProcessingException exception) {
				return new ErrorResponse(null, "Error Creating Message for Job", "Gateway");
			}
			// Dispatch the Kafka Message in a separate thread.
			System.out.println("Requesting Job topic " + message.topic() + " with key " + message.key());
			(new Thread() {
				public void run() {
					producer.send(message);
				}
			}).start();

			// Respond immediately with the new Job GUID
			return new PiazzaResponse(jobId);
		}
	}

}