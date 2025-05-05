package com.example.videoeditor.service;

import com.example.videoeditor.developer.entity.Developer;
import com.example.videoeditor.developer.repository.DeveloperRepository;
import com.example.videoeditor.dto.AuthRequest;
import com.example.videoeditor.dto.AuthResponse;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.VerificationToken;
import com.example.videoeditor.repository.UserRepository;
import com.example.videoeditor.repository.VerificationTokenRepository;
import com.example.videoeditor.security.JwtUtil;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import jakarta.mail.MessagingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.Record;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final VerificationTokenRepository verificationTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final EmailService emailService;
    private final DeveloperRepository developerRepository;

    @Value("${spring.security.oauth2.client.registration.google.client-id}")
    private String googleClientId;

    public AuthService(UserRepository userRepository, VerificationTokenRepository verificationTokenRepository,
                       PasswordEncoder passwordEncoder, JwtUtil jwtUtil, EmailService emailService, DeveloperRepository developerRepository) {
        this.userRepository = userRepository;
        this.verificationTokenRepository = verificationTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.emailService = emailService;
        this.developerRepository = developerRepository;
    }

    @Transactional
    public AuthResponse register(AuthRequest request) throws MessagingException {
        // Enhanced email validation
        if (!isValidEmail(request.getEmail())) {
            throw new RuntimeException("Please enter a valid email address. The email does not exist or cannot receive mail.");
        }

        // Check email domain validity
        if (!isEmailDomainValid(request.getEmail())) {
            throw new RuntimeException("The email domain is not valid");
        }

        Optional<User> existingUser = userRepository.findByEmail(request.getEmail());
        if (existingUser.isPresent()) {
            User user = existingUser.get();
            if (user.isGoogleAuth()) {
                throw new RuntimeException("Email is already associated with a Google account. Please log in with Google.");
            }
            throw new RuntimeException("This email is already registered");
        }

        // Validate password strength
        if (!isPasswordValid(request.getPassword())) {
            throw new RuntimeException("Password must be at least 8 characters with at least one letter and one number");
        }

        // Create and save user
        User user = new User();
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setGoogleAuth(false);
        user.setEmailVerified(false);
        userRepository.save(user);

        // Generate and save verification token
        String token = UUID.randomUUID().toString();
        VerificationToken verificationToken = new VerificationToken(
                token,
                user,
                LocalDateTime.now().plusHours(24)
        );
        System.out.println("Saving verification token: " + token); // Debug log
        verificationTokenRepository.save(verificationToken);
        System.out.println("Verification token saved for user: " + user.getEmail()); // Debug log

        // Send verification email
        try {
            emailService.sendVerificationEmail(user.getEmail(), token);
        } catch (MessagingException e) {
            // If email fails to send, delete the user
            userRepository.delete(user);
            throw new RuntimeException("Failed to send verification email. Please check your email address.");
        }

        return new AuthResponse(
                null,
                user.getEmail(),
                null,
                "Verification email sent. Please check your inbox to complete registration.",
                false
        );
    }

    private boolean isEmailDomainValid(String email) {
        try {
            String domain = email.substring(email.indexOf('@') + 1);
            // Check if domain has a valid structure
            return domain.matches("^([a-z0-9]+(-[a-z0-9]+)*\\.)+[a-z]{2,}$");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isPasswordValid(String password) {
        // At least 8 chars, contains letter and number
        return password != null && password.length() >= 8 &&
                password.matches(".*[a-zA-Z].*") &&
                password.matches(".*[0-9].*");
    }

    public AuthResponse login(AuthRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("Invalid credentials"));

        if (user.isGoogleAuth()) {
            throw new RuntimeException("This account is linked to Google. Please log in with Google.");
        }

        if (!user.isEmailVerified()) {
            throw new RuntimeException("Email not verified. Please check your inbox for the verification email.");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid credentials");
        }

        String token = jwtUtil.generateToken(user.getEmail());
        return new AuthResponse(
                token,
                user.getEmail(),
                user.getName(),
                "Login successful",
                true
        );
    }

    public AuthResponse googleLogin(String idTokenString) throws Exception {
        GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(googleClientId))
                .build();

        GoogleIdToken idToken = verifier.verify(idTokenString);
        if (idToken == null) {
            throw new RuntimeException("Invalid Google ID token");
        }

        GoogleIdToken.Payload payload = idToken.getPayload();
        String email = payload.getEmail();
        String name = (String) payload.get("name");
        String picture = (String) payload.get("picture");  // Get the profile picture URL

        // Log the picture URL from Google
        System.out.println("Google profile picture URL: " + picture);

        Optional<User> existingUserOpt = userRepository.findByEmail(email);
        User user;
        if (existingUserOpt.isPresent()) {
            user = existingUserOpt.get();
            if (name != null && (user.getName() == null || user.getName().isEmpty())) {
                user.setName(name);
            }
            // Update profile picture from Google
            if (picture != null) {
                user.setProfilePicture(picture);
            }
            user.setGoogleAuth(true);
            if (user.getPassword() == null || user.getPassword().isEmpty()) {
                user.setPassword(passwordEncoder.encode("GOOGLE_AUTH_" + System.currentTimeMillis()));
            }
            user.setEmailVerified(true);
            userRepository.save(user);
        } else {
            user = new User();
            user.setEmail(email);
            user.setName(name);
            user.setProfilePicture(picture);  // Set profile picture for new users
            user.setPassword(passwordEncoder.encode("GOOGLE_AUTH_" + System.currentTimeMillis()));
            user.setGoogleAuth(true);
            user.setEmailVerified(true);  // Set email as verified for Google users
            userRepository.save(user);
        }

        String token = jwtUtil.generateToken(user.getEmail());
        return new AuthResponse(
                token,
                user.getEmail(),
                user.getName(),
                "Google login successful",
                true
        );
    }

    public String verifyEmail(String token) {
        System.out.println("Verifying token: " + token);
        VerificationToken verificationToken = verificationTokenRepository.findByToken(token)
                .orElseThrow(() -> {
                    System.out.println("Token not found: " + token);
                    return new RuntimeException("Invalid or unknown verification token");
                });

        System.out.println("Token expiry: " + verificationToken.getExpiryDate());
        if (verificationToken.getExpiryDate().isBefore(LocalDateTime.now())) {
            verificationTokenRepository.delete(verificationToken);
            System.out.println("Token expired: " + token);
            throw new RuntimeException("Verification token has expired. Please request a new verification email.");
        }

        User user = verificationToken.getUser();
        System.out.println("User email: " + user.getEmail() + ", verified: " + user.isEmailVerified());
        if (user.isEmailVerified() || verificationToken.isVerified()) {
            System.out.println("Email already verified for user: " + user.getEmail());
            throw new RuntimeException("Email has already been verified. Please log in.");
        }

        user.setEmailVerified(true);
        verificationToken.setVerified(true);
        userRepository.save(user);
        verificationTokenRepository.save(verificationToken);
        System.out.println("Email verified successfully for user: " + user.getEmail());

        String jwtToken = jwtUtil.generateToken(user.getEmail());
        System.out.println("Generated JWT token: " + jwtToken);
        return jwtToken;
    }


    @Transactional
    public void resendVerificationEmail(String email) throws MessagingException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.isEmailVerified()) {
            throw new RuntimeException("Email already verified");
        }

        // Delete existing tokens for the user and flush to ensure deletion is committed
        System.out.println("Deleting existing verification tokens for user: " + email);
        verificationTokenRepository.deleteByUser(user);
        verificationTokenRepository.flush(); // Ensure deletion is committed
        System.out.println("Existing verification tokens deleted for user: " + email);

        // Generate and save new token
        String token = UUID.randomUUID().toString();
        VerificationToken verificationToken = new VerificationToken(
                token,
                user,
                LocalDateTime.now().plusHours(24)
        );
        System.out.println("Saving new verification token: " + token);
        verificationTokenRepository.saveAndFlush(verificationToken); // Ensure save is committed
        System.out.println("New verification token saved for user: " + email);

        // Send verification email
        emailService.sendVerificationEmail(user.getEmail(), token);
        System.out.println("Verification email sent to: " + email);
    }

    private boolean isValidEmail(String email) {
        // Existing regex check
        String emailRegex = "^[A-Za-z0-9+_.-]+@(.+)$";
        if (email == null || !email.matches(emailRegex)) {
            return false;
        }

        // Add SMTP-based email validation
        return verifyEmailExists(email);
    }

    private boolean verifyEmailExists(String email) {
        try {
            // Extract domain from email
            String domain = email.substring(email.indexOf('@') + 1);

            // Resolve MX records for the domain
            Lookup lookup = new Lookup(domain, Type.MX);
            Record[] records = lookup.run();
            if (records == null || records.length == 0) {
                System.out.println("No MX records found for domain: " + domain);
                return false; // No MX records found
            }

            // Find the first MXRecord
            MXRecord mxRecord = null;
            for (Record record : records) {
                if (record instanceof MXRecord) {
                    mxRecord = (MXRecord) record;
                    break;
                }
            }
            if (mxRecord == null) {
                System.out.println("No MXRecord found in records for domain: " + domain);
                return false; // No MXRecord found
            }

            // Get the MX host
            String mxHost = mxRecord.getTarget().toString();

            // Connect to the mail server
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(mxHost, 25), 5000); // 5-second timeout

                // Perform SMTP handshake (simplified)
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

                // Read server greeting
                String greeting = reader.readLine();
                if (greeting == null || !greeting.startsWith("220")) {
                    System.out.println("Invalid SMTP greeting: " + greeting);
                    return false;
                }

                // Send HELO command
                writer.write("HELO example.com\r\n");
                writer.flush();
                String heloResponse = reader.readLine();
                if (heloResponse == null || !heloResponse.startsWith("250")) {
                    System.out.println("Invalid HELO response: " + heloResponse);
                    return false;
                }

                // Send MAIL FROM command
                writer.write("MAIL FROM:<verify@example.com>\r\n");
                writer.flush();
                String mailFromResponse = reader.readLine();
                if (mailFromResponse == null || !mailFromResponse.startsWith("250")) {
                    System.out.println("Invalid MAIL FROM response: " + mailFromResponse);
                    return false;
                }

                // Send RCPT TO command to check recipient
                writer.write("RCPT TO:<" + email + ">\r\n");
                writer.flush();
                String rcptToResponse = reader.readLine();
                if (rcptToResponse == null) {
                    System.out.println("No response for RCPT TO");
                    return false;
                }

                // Check if the response code is 250 (recipient OK)
                boolean isValid = rcptToResponse.startsWith("250");
                if (!isValid) {
                    System.out.println("RCPT TO response indicates email does not exist: " + rcptToResponse);
                }
                return isValid;
            }
        } catch (TextParseException e) {
            System.out.println("Failed to parse domain for MX lookup: " + e.getMessage());
            return false;
        } catch (Exception e) {
            System.out.println("Email verification failed for: " + email + ", error: " + e.getMessage());
            return false;
        }
    }

    @Transactional
    public AuthResponse developerLogin(AuthRequest request) {
        Developer developer = developerRepository.findByUsername(request.getEmail())
                .orElseThrow(() -> new RuntimeException("Invalid developer credentials"));

        if (!developer.isActive()) {
            throw new RuntimeException("Developer account is disabled");
        }

        if (!passwordEncoder.matches(request.getPassword(), developer.getPassword())) {
            throw new RuntimeException("Invalid developer credentials");
        }

        String token = jwtUtil.generateToken(developer.getUsername(), "DEVELOPER");
        return new AuthResponse(
                token,
                developer.getUsername(),
                null,
                "Developer login successful",
                true
        );
    }

    @Transactional
    public Developer createDeveloper(String username, String password) {
        if (developerRepository.findByUsername(username).isPresent()) {
            throw new RuntimeException("Developer username already exists");
        }

        if (!isPasswordValid(password)) {
            throw new RuntimeException("Password must be at least 8 characters with at least one letter and one number");
        }

        Developer developer = new Developer();
        developer.setUsername(username);
        developer.setPassword(passwordEncoder.encode(password));
        developer.setActive(true);
        return developerRepository.save(developer);
    }
}