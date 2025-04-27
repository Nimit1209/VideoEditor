package com.example.videoeditor.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Service
public class EmailService {
    private final JavaMailSender mailSender;

    @Value("${app.frontend.url}")
    private String frontendUrl; // e.g., http://localhost:3000

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendVerificationEmail(String to, String token) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true);
        helper.setTo(to);
        helper.setSubject("Verify Your Email - VideoCraft");

        String verificationUrl = "http://localhost:3000/verify-email?token=" + token;
        String htmlContent = "<h3>Welcome to VideoCraft!</h3>" +
                "<p>Please verify your email by clicking the link below:</p>" +
                "<a href=\"" + verificationUrl + "\">Verify Email</a>" +
                "<p>This link will expire in 24 hours.</p>";
        helper.setText(htmlContent, true);

        mailSender.send(message);
    }
}