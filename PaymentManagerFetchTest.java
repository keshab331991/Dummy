package com.svb.gateway.payments.payment.service.manager;

import com.svb.gateway.payments.common.dto.RequestData;
import com.svb.gateway.payments.common.enums.ErrorCodeEnum;
import com.svb.gateway.payments.common.enums.payment.*;
import com.svb.gateway.payments.common.exception.PaymentServiceException;
import com.svb.gateway.payments.common.model.PaymentContext;
import com.svb.gateway.payments.common.model.payment.*;
import com.svb.gateway.payments.common.model.payment.initiation.*;
import com.svb.gateway.payments.common.service.PayeeIntegrationService;
import com.svb.gateway.payments.common.util.DateUtil;
import com.svb.gateway.payments.payment.entity.*;
import com.svb.gateway.payments.payment.mapper.db.*;
import com.svb.gateway.payments.payment.mapper.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(SpringExtension.class)
class PaymentManagerFetchTest {

    @InjectMocks
    private PaymentManagerFetch manager;

    @Mock private PaymentMapper paymentMapper;
    @Mock private PaymentDBMapper paymentDBMapper;
    @Mock private PaymentEntryDBMapper paymentEntryDBMapper;
    @Mock private TransactionMapper transactionMapper;
    @Mock private TransactionDBMapper transactionDBMapper;
    @Mock private PaymentDetailMapper paymentDetailMapper;
    @Mock private PayeeIntegrationService payeesService;
    @Mock private DateUtil dateUtil;

    private PaymentContext context;

    @BeforeEach
    void setup() {
        context = mock(PaymentContext.class);
        when(context.getClientId()).thenReturn("CLIENT1");
        when(context.getUserId()).thenReturn("USER1");
        when(context.isApprovalFlag()).thenReturn(false);
    }

    // ---------- populatePaymentEntity ----------

    @Test
    void populatePaymentEntity_shouldSetStatusAndClientFields() {
        PaymentEntity payment = new PaymentEntity();
        RequestData<PaymentInitiationData> request = new RequestData<>();
        PaymentInitiationData data = mock(PaymentInitiationData.class);
        RecurringData recurring = mock(RecurringData.class);

        request.setRequest(data);
        when(data.isHold()).thenReturn(false);
        when(data.getRecurringData()).thenReturn(recurring);
        when(recurring.isRecurring()).thenReturn(false);

        manager.populatePaymentEntity(context, payment, request);

        assertEquals("CLIENT1", payment.getClientId());
        assertEquals("USER1", payment.getUserId());
        assertEquals(PaymentStatus.COMP.toString(), payment.getStatus());
    }

    // ---------- populatePaymentEntryEntity ----------

    @Test
    void populatePaymentEntryEntity_shouldDefaultCcyAmt() {
        PaymentEntryEntity entity = new PaymentEntryEntity();
        PaymentInitiationEntryData entry = mock(PaymentInitiationEntryData.class);
        AccountData debit = mock(AccountData.class);

        when(entry.getDebitAccountData()).thenReturn(debit);
        when(debit.getCcyAmt()).thenReturn(0.0);
        when(entry.getTransactionAmt()).thenReturn(100.0);

        manager.populatePaymentEntryEntity(context, entity, entry);

        verify(debit).setCcyAmt(100.0);
        assertEquals("USER1", entity.getCreatedBy());
    }

    // ---------- populateTransactionEntity ----------

    @Test
    void populateTransactionEntity_shouldPopulateAuditFields() {
        TransactionEntity entity = new TransactionEntity();
        RequestData<PaymentInitiationData> request = new RequestData<>();

        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        when(dateUtil.getApplicationTimestamp()).thenReturn(now);

        manager.populateTransactionEntity(context, entity, request);

        assertEquals(TransactionStatus.INPR.toString(), entity.getStatus());
        assertEquals("CLIENT1", entity.getClientId());
        assertEquals("USER1", entity.getUserId());
        assertEquals(now, entity.getCreatedTimestamp());
    }

    // ---------- fetchPayment(paymentId) ----------

    @Test
    void fetchPayment_shouldThrowWhenClientMismatch() {
        PaymentEntity entity = new PaymentEntity();
        entity.setClientId("OTHER");

        when(paymentDBMapper.getPaymentById(1L)).thenReturn(entity);

        PaymentServiceException ex = assertThrows(
                PaymentServiceException.class,
                () -> manager.fetchPayment(1L, "CLIENT1", null)
        );

        assertEquals(ErrorCodeEnum.PAYMENT_NOT_FOUND, ex.getErrorCode());
    }

    // ---------- fetchTransaction ----------

    @Test
    void fetchTransaction_shouldThrowWhenNotFound() {
        when(transactionDBMapper.getTransactionById(10L)).thenReturn(null);

        assertThrows(
                PaymentServiceException.class,
                () -> manager.fetchTransaction(10L, 0L, null, null)
        );
    }

    // ---------- fetchPayment duplicate ----------

    @Test
    void fetchPaymentDuplicate_shouldReturnDuplicate() {
        PaymentInitiationData data = mock(PaymentInitiationData.class);
        PaymentInitiationEntryData entry = mock(PaymentInitiationEntryData.class);

        when(data.getEntries()).thenReturn(List.of(entry));
        when(entry.getPayeeCategory()).thenReturn(PayeeCategory.P);
        when(entry.getTransactionAmt()).thenReturn(10.0);
        when(entry.getPayeeId()).thenReturn(1L);
        when(entry.getPayeeTypeId()).thenReturn(1L);

        PaymentEntryEntity entity = new PaymentEntryEntity();
        entity.setPaymentId(99L);
        entity.setSequenceNo(1);

        when(paymentEntryDBMapper.getDuplicatePaymentPersonalPayee(
                any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(List.of(entity));

        DuplicateCheckResponse response =
                manager.fetchPayment("CLIENT1", "USER1", data);

        assertEquals("99", response.getPaymentId());
        assertEquals("1", response.getSequenceNo());
    }

    // ---------- getCriteriaDate ----------

    @Test
    void getCriteriaDate_shouldReturnNullOnInvalidDate() throws Exception {
        var method = PaymentManagerFetch.class
                .getDeclaredMethod("getCriteriaDate", String.class, int.class);
        method.setAccessible(true);

        when(dateUtil.getTimestamp(anyString())).thenThrow(new RuntimeException());

        assertNull(method.invoke(manager, "BAD_DATE", 0));
    }

    // ---------- populateAdditionalDetails ----------

    @Test
    void populateAdditionalDetails_shouldNotThrow() {
        PaymentInitiationData initiationData = mock(PaymentInitiationData.class);
        PaymentCancellationData cancellation = mock(PaymentCancellationData.class);

        when(initiationData.getEntries()).thenReturn(List.of(mock(PaymentInitiationEntryData.class)));

        assertDoesNotThrow(() ->
                manager.populateAdditionalDetails(cancellation, initiationData)
        );
    }
}
