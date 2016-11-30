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
package gateway.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import model.logger.AuditElement;
import model.logger.Severity;
import model.response.AuthenticationResponse;
import util.PiazzaLogger;

/**
 * Bean that communicates with the pz-security project for authentication information.
 * 
 * @author Russell.Orf
 * 
 */
@Service
public class UserDetailsBean {
	@Value("${security.url}")
	private String SECURITY_URL;
	@Autowired
	private RestTemplate restTemplate;
	@Autowired
	private PiazzaLogger logger;

	public AuthenticationResponse getAuthenticationDecision(String uuid) {
		String url = String.format("%s/%s", SECURITY_URL, "/v2/verification");
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<PiazzaVerificationRequest> request = new HttpEntity<>(new PiazzaVerificationRequest(uuid), headers);
		AuthenticationResponse response = restTemplate.postForEntity(url, request, AuthenticationResponse.class).getBody();
		String actionName = response.getAuthenticated() ? "keyVerified" : "keyDeclined";
		logger.log(String.format("Checked Verification for API Key %s with verified = %s", uuid, response.getAuthenticated()),
				Severity.INFORMATIONAL, new AuditElement(uuid, actionName, ""));
		return response;
	}

	class PiazzaVerificationRequest {
		private String uuid;

		PiazzaVerificationRequest(String uuid) {
			this.uuid = uuid;
		}

		public String getUuid() {
			return uuid;
		}
	}
}