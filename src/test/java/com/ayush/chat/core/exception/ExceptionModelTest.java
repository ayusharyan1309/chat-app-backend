package com.ayush.chat.core.exception;

import com.ayush.chat.core.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionModelTest {

    @Test
    void factoriesReturnTypedExceptionsWithTheirCodes() {
        assertThat(DomainException.badRequest("x")).isInstanceOf(BadRequestException.class)
                .extracting(DomainException::getErrorCode).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(DomainException.notFound("Conversation")).isInstanceOf(NotFoundException.class)
                .hasMessage("Conversation not found");
        assertThat(DomainException.forbidden("x")).isInstanceOf(ForbiddenException.class);
        assertThat(DomainException.unauthorized("x")).isInstanceOf(UnauthorizedException.class);
        assertThat(DomainException.conflict("x")).isInstanceOf(ConflictException.class);
        assertThat(DomainException.quotaExceeded("x")).isInstanceOf(QuotaExceededException.class);
        assertThat(DomainException.rateLimited("x")).isInstanceOf(RateLimitedException.class);
        FeatureDisabledException disabled = DomainException.featureDisabled("group-chat");
        assertThat(disabled.getFeatureId()).isEqualTo("group-chat");
        assertThat(disabled.getCode()).isEqualTo("feature_disabled");
    }

    @Test
    void everyCodeHasAnHttpStatus() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(ErrorHttpMapping.status(code)).as(code.name()).isNotNull();
        }
        assertThat(ErrorHttpMapping.status(ErrorCode.FEATURE_DISABLED)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ErrorHttpMapping.status(ErrorCode.QUOTA_EXCEEDED)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ErrorHttpMapping.status(ErrorCode.UNAVAILABLE)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void errorBodyCarriesTheStableCode() {
        ErrorResponse body = ErrorHttpMapping.body(ErrorCode.NOT_FOUND, "Tenant 'x' not found", "/api/admin/tenants/x");
        assertThat(body.status()).isEqualTo(404);
        assertThat(body.error()).isEqualTo("not_found");
        assertThat(body.path()).isEqualTo("/api/admin/tenants/x");
        assertThat(body.timestamp()).isNotNull();
    }

    @Test
    void backendFailuresAreRetryableAndVendorNeutral() {
        InfrastructureUnavailableException e = new InfrastructureUnavailableException("Message store", new RuntimeException("PSQL"));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAVAILABLE);
        assertThat(e.getMessage()).isEqualTo("Message store is temporarily unavailable").doesNotContain("PSQL");
    }
}
