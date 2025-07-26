package com.example.jettison;

import build.buf.protovalidate.Validator;
import build.buf.protovalidate.Config;
import build.buf.protovalidate.results.ValidationResult;
import build.buf.protovalidate.results.ValidationException;
import build.buf.protovalidate.results.Violation;

// Import your generated proto classes
import cmd.JonSharedCmd;
import ser.JonSharedDataTypes;

/**
 * Example of using protovalidate-java with Jettison proto messages.
 * 
 * This demonstrates how to validate messages that use buf.validate annotations
 * like the ones in jon_shared_cmd.proto.
 */
public class JavaValidationExample {
    
    public static void main(String[] args) {
        // Create a validator with default configuration
        Validator validator = new Validator();
        
        // Example 1: Create a valid Root message
        JonSharedCmd.Root validRoot = JonSharedCmd.Root.newBuilder()
            .setProtocolVersion(1)  // Must be > 0 per validation rule
            .setSessionId(12345)
            .setImportant(true)
            .setFromCvSubsystem(false)
            .setClientType(JonSharedDataTypes.JonGuiDataClientType.JON_GUI_DATA_CLIENT_TYPE_STANDALONE)
            // Set a payload - the oneof is required per validation rule
            .setPing(JonSharedCmd.Ping.newBuilder().build())
            .build();
        
        try {
            // Validate the message
            ValidationResult result = validator.validate(validRoot);
            if (result.isSuccess()) {
                System.out.println("✓ Valid message passed validation!");
            }
        } catch (ValidationException e) {
            System.err.println("✗ Validation failed: " + e.getMessage());
            for (Violation violation : e.getViolations().getViolationsList()) {
                System.err.println("  - Field: " + violation.getFieldPath());
                System.err.println("    Constraint: " + violation.getConstraintId());
                System.err.println("    Message: " + violation.getMessage());
            }
        }
        
        // Example 2: Create an invalid Root message (missing required fields)
        JonSharedCmd.Root invalidRoot = JonSharedCmd.Root.newBuilder()
            .setProtocolVersion(0)  // Invalid: must be > 0
            .setSessionId(12345)
            .setImportant(true)
            .setFromCvSubsystem(false)
            // Missing client_type (enum must be defined_only and not_in: [0])
            // Missing payload (oneof is required)
            .build();
        
        try {
            validator.validate(invalidRoot);
            System.out.println("✗ Should have failed validation!");
        } catch (ValidationException e) {
            System.out.println("✓ Invalid message correctly failed validation:");
            for (Violation violation : e.getViolations().getViolationsList()) {
                System.out.println("  - " + violation.getFieldPath() + ": " + violation.getMessage());
            }
        }
        
        // Example 3: Validate with custom configuration
        Config customConfig = Config.newBuilder()
            .setFailFast(true)  // Stop at first validation error
            .build();
        
        Validator customValidator = new Validator(customConfig);
        
        // Use the custom validator...
        System.out.println("\nUsing custom validator with fail-fast enabled.");
    }
}