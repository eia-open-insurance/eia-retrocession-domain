package com.eia.retrocession.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RetrocessionController.class)
@Import(RetrocessionService.class)
class RetrocessionControllerTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID CONTRACT = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID RETRO_CONTRACT = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final Instant HANDED_OFF_AT = Instant.parse("2026-10-19T12:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RetrocessionClient client;

    @MockitoBean
    private FsriRetroClient fsriClient;

    private static RetrocessionDto dto(RetrocessionStatus status, int version) {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new RetrocessionDto(ID, TENANT, "ASSUMED", "RET-000000001", CONTRACT, RETRO_CONTRACT, status,
                new BigDecimal("1000.0000"), new BigDecimal("250.0000"), "USD", version, now, now,
                null, null, null, null);
    }

    private static RetrocessionDto handedOffDto() {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new RetrocessionDto(ID, TENANT, "ASSUMED", "RET-000000001", CONTRACT, RETRO_CONTRACT,
                RetrocessionStatus.HANDED_OFF, new BigDecimal("1000.0000"), new BigDecimal("250.0000"),
                "USD", 1, now, now,
                "FSRI-RET-ASSUMED-000001", "POSTED", HANDED_OFF_AT, null);
    }

    private static FsriHandoffConfirmation confirmation() {
        return new FsriHandoffConfirmation(
                "FSRI-RET-ASSUMED-000001", "POSTED", false, "FSRI-CNF-ASSUMED-000001",
                new BigDecimal("250.0000"), new BigDecimal("250.0000"), new BigDecimal("0.0000"),
                "USD", HANDED_OFF_AT, "fsri-retro-" + ID + "-v1");
    }

    @Test
    void listRequiresTenantHeader() throws Exception {
        mvc.perform(get("/api/v1/retrocessions").header("X-EIA-Book", "ASSUMED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }

    @Test
    void listRequiresBookHeader() throws Exception {
        mvc.perform(get("/api/v1/retrocessions").header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BOOK_REQUIRED"));
    }

    @Test
    void listHidesDirectBookAs404() throws Exception {
        mvc.perform(get("/api/v1/retrocessions")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOK_SCOPE_VIOLATION"))
                .andExpect(jsonPath("$.message")
                        .value("Retrocession is an ASSUMED-only vertical; X-EIA-Book must be ASSUMED"));
    }

    @Test
    void wrongBookOnCreateReturns404WithoutCallingExpert() throws Exception {
        mvc.perform(post("/api/v1/retrocessions")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contractId": "%s",
                                  "retroContractId": "%s",
                                  "cededPremium": 1000.0000,
                                  "cededLoss": 250.0000,
                                  "currency": "USD"
                                }
                                """.formatted(CONTRACT, RETRO_CONTRACT)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOK_SCOPE_VIOLATION"));

        verifyNoInteractions(client);
    }

    @Test
    void rejectsMalformedTenantHeader() throws Exception {
        mvc.perform(get("/api/v1/retrocessions")
                        .header("X-EIA-Tenant", "not-a-uuid")
                        .header("X-EIA-Book", "ASSUMED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TENANT_INVALID"));
    }

    @Test
    void listsRetrocessionsForAssumedBook() throws Exception {
        when(client.findAll(TENANT)).thenReturn(List.of(dto(RetrocessionStatus.OPEN, 0)));

        mvc.perform(get("/api/v1/retrocessions")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-EIA-Book", "ASSUMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].retrocessionNumber").value("RET-000000001"))
                .andExpect(jsonPath("$[0].businessBook").value("ASSUMED"))
                .andExpect(jsonPath("$[0].status").value("OPEN"));
    }

    @Test
    void moneyFieldsAreSerializedAsDecimalStrings() throws Exception {
        when(client.findAll(TENANT)).thenReturn(List.of(dto(RetrocessionStatus.OPEN, 0)));

        mvc.perform(get("/api/v1/retrocessions")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-EIA-Book", "ASSUMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cededPremium").isString())
                .andExpect(jsonPath("$[0].cededPremium").value("1000.0000"))
                .andExpect(jsonPath("$[0].cededLoss").isString())
                .andExpect(jsonPath("$[0].cededLoss").value("250.0000"));
    }

    @Test
    void returnsStructured404ForMissingRetrocession() throws Exception {
        when(client.findById(TENANT, ID))
                .thenThrow(DomainException.notFound("Retrocession not found: " + ID));

        mvc.perform(get("/api/v1/retrocessions/{id}", ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-EIA-Book", "ASSUMED"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Retrocession not found: " + ID));
    }

    @Test
    void createsRetrocession() throws Exception {
        when(client.create(eq(TENANT), any())).thenReturn(dto(RetrocessionStatus.OPEN, 0));

        mvc.perform(post("/api/v1/retrocessions")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-EIA-Book", "ASSUMED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contractId": "%s",
                                  "retroContractId": "%s",
                                  "cededPremium": 1000.0000,
                                  "cededLoss": 250.0000,
                                  "currency": "USD"
                                }
                                """.formatted(CONTRACT, RETRO_CONTRACT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.retrocessionNumber").value("RET-000000001"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.businessBook").value("ASSUMED"));
    }

    @Test
    void rejectsNegativeCededLossBeforeCallingExpert() throws Exception {
        mvc.perform(post("/api/v1/retrocessions")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-EIA-Book", "ASSUMED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contractId": "%s",
                                  "retroContractId": "%s",
                                  "cededPremium": 1000.0000,
                                  "cededLoss": -0.0100,
                                  "currency": "USD"
                                }
                                """.formatted(CONTRACT, RETRO_CONTRACT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.details.cededLoss").value("must be zero or greater"));
    }

    @Test
    void rejectsSkippedLifecycleTransition() throws Exception {
        when(client.findById(TENANT, ID)).thenReturn(dto(RetrocessionStatus.OPEN, 0));

        mvc.perform(post("/api/v1/retrocessions/{id}/transition", ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-EIA-Book", "ASSUMED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetStatus": "CONFIRMED"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void advancesStatusThroughDomain() throws Exception {
        when(client.findById(TENANT, ID)).thenReturn(dto(RetrocessionStatus.OPEN, 0));
        when(client.updateStatus(TENANT, ID, RetrocessionStatus.RULE_APPLIED, 0))
                .thenReturn(dto(RetrocessionStatus.RULE_APPLIED, 1));

        mvc.perform(post("/api/v1/retrocessions/{id}/transition", ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-EIA-Book", "ASSUMED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetStatus": "RULE_APPLIED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RULE_APPLIED"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void handoffTransitionCallsFsriAndReturnsHandedOffCaseWithFields() throws Exception {
        when(client.findById(TENANT, ID)).thenReturn(dto(RetrocessionStatus.RULE_APPLIED, 0));
        when(fsriClient.handoff(any())).thenReturn(confirmation());
        when(client.recordHandoff(eq(TENANT), eq(ID), any())).thenReturn(handedOffDto());

        mvc.perform(post("/api/v1/retrocessions/{id}/transition", ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-EIA-Book", "ASSUMED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetStatus": "HANDOFF_PENDING", "fsriContractNumber": "RET-2026-0001"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HANDED_OFF"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.fsriHandoffId").value("FSRI-RET-ASSUMED-000001"))
                .andExpect(jsonPath("$.fsriStatus").value("POSTED"))
                .andExpect(jsonPath("$.fsriHandedOffAt").value("2026-10-19T12:00:00Z"));

        verify(fsriClient).handoff(argThat(handoff ->
                handoff.transitionVersion() == 1
                        && "RET-2026-0001".equals(handoff.contractNumber())
                        && handoff.expectedAmount().compareTo(new BigDecimal("250.0000")) == 0));
        verify(client).recordHandoff(eq(TENANT), eq(ID), argThat(record ->
                record.expectedVersion() == 0
                        && "FSRI-RET-ASSUMED-000001".equals(record.fsriHandoffId())
                        && "POSTED".equals(record.fsriStatus())));
        verify(client, never()).updateStatus(TENANT, ID, RetrocessionStatus.HANDED_OFF, 0);
    }

    @Test
    void fsriRejectionReturnsStructured400AndRecordsFailure() throws Exception {
        when(client.findById(TENANT, ID)).thenReturn(dto(RetrocessionStatus.RULE_APPLIED, 0));
        when(fsriClient.handoff(any())).thenThrow(new DomainException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "FSRI_REJECTED",
                "FS-RI rejected the retro hand-off", java.util.Map.of("reasonCode", "FSRI-RETRO-REJECTED")));
        when(client.recordHandoffFailure(eq(TENANT), eq(ID), any()))
                .thenReturn(dto(RetrocessionStatus.RULE_APPLIED, 1));

        mvc.perform(post("/api/v1/retrocessions/{id}/transition", ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-EIA-Book", "ASSUMED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetStatus": "HANDOFF_PENDING", "fsriContractNumber": "RET-2026-0001"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FSRI_REJECTED"))
                .andExpect(jsonPath("$.details.reasonCode").value("FSRI-RETRO-REJECTED"));

        verify(client, never()).recordHandoff(any(), any(), any());
        verify(client, never()).updateStatus(any(), any(), any(), anyInt());
        verify(client).recordHandoffFailure(eq(TENANT), eq(ID), argThat(failure ->
                failure.expectedVersion() == 0
                        && failure.fsriLastError().contains("FSRI_REJECTED")));
    }

    @Test
    void fsriTimeoutReturns503AndLeavesCaseRetryable() throws Exception {
        when(client.findById(TENANT, ID)).thenReturn(dto(RetrocessionStatus.RULE_APPLIED, 0));
        when(fsriClient.handoff(any())).thenThrow(DomainException.fsriUnavailable(
                "FS-RI did not respond before the configured timeout", java.util.Map.of("cause", "ResourceAccessException")));
        when(client.recordHandoffFailure(eq(TENANT), eq(ID), any()))
                .thenReturn(dto(RetrocessionStatus.RULE_APPLIED, 1));

        mvc.perform(post("/api/v1/retrocessions/{id}/transition", ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-EIA-Book", "ASSUMED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetStatus": "HANDOFF_PENDING", "fsriContractNumber": "RET-2026-0001"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FSRI_UNAVAILABLE"))
                .andExpect(jsonPath("$.details.cause").value("ResourceAccessException"));

        verify(client, never()).recordHandoff(any(), any(), any());
    }

    @Test
    void rejectsInvalidFsriContractNumberAtTheApiBoundary() throws Exception {
        mvc.perform(post("/api/v1/retrocessions/{id}/transition", ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-EIA-Book", "ASSUMED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetStatus": "HANDOFF_PENDING", "fsriContractNumber": "lower-case"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.details.fsriContractNumber").exists());

        verifyNoInteractions(client, fsriClient);
    }

    @Test
    void reportsExpertOutageAs503() throws Exception {
        when(client.findAll(TENANT))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("connection refused"));

        mvc.perform(get("/api/v1/retrocessions")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .header("X-EIA-Book", "ASSUMED"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EXPERT_UNAVAILABLE"));
    }
}
