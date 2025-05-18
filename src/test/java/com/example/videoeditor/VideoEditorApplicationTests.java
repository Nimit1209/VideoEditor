package com.example.videoeditor;

import com.example.videoeditor.config.TestConfig;
import com.example.videoeditor.service.BackblazeB2Service;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(locations = "classpath:application-test.properties")
public class VideoEditorApplicationTests {

	@Autowired
	private BackblazeB2Service backblazeB2Service;

	@Test
	void contextLoads() {
		// Verify that the Spring context loads correctly
		assertNotNull(backblazeB2Service);
	}

	@Test
	void backblazeB2ServiceIsMocked() {
		// Verify that the injected BackblazeB2Service is a mock
		assertTrue(Mockito.mockingDetails(backblazeB2Service).isMock(), "BackblazeB2Service should be a mock");
	}
}