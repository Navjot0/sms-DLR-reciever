package com.smsframework.dlr.billing;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Row of dlr_billing_events (one per billing event received). */
public class DlrBillingEvent {

    private Long id;
    private String source;
    private UUID batchId;
    private int batchIndex;
    private String messageId;
    private String billingMessageId;
    private Integer partNumber;
    private String transactionType;
    private String product;
    private Integer units;
    private BigDecimal salePrice;
    private String currency;
    private BigDecimal surcharge;
    private BigDecimal totalAmount;
    private String rawEvent;
    private String rawPayload;
    private String processingStatus;
    private String processingNote;
    private String rejectionReason;
    private String dedupKey;
    private Long duplicateOf;
    private String receiverInstance;
    private OffsetDateTime createdAt;

    public static DlrBillingEvent from(NormalizedBillingEvent e, String source, UUID batchId, String rawPayload,
                                       String instance) {
        DlrBillingEvent b = new DlrBillingEvent();
        b.source = source;
        b.batchId = batchId;
        b.batchIndex = e.batchIndex();
        b.messageId = e.messageId();
        b.billingMessageId = e.billingMessageId();
        b.partNumber = e.partNumber();
        b.transactionType = e.transactionType();
        b.product = e.product();
        b.units = e.units();
        b.salePrice = e.salePrice();
        b.currency = e.currency();
        b.surcharge = e.surcharge();
        b.totalAmount = e.totalAmount();
        b.rawEvent = e.rawEvent();
        b.rawPayload = rawPayload;
        b.rejectionReason = e.rejectionReason();
        b.receiverInstance = instance;
        b.processingStatus = e.valid() ? "APPLIED" : "REJECTED";
        return b;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public UUID getBatchId() { return batchId; }
    public void setBatchId(UUID batchId) { this.batchId = batchId; }
    public int getBatchIndex() { return batchIndex; }
    public void setBatchIndex(int batchIndex) { this.batchIndex = batchIndex; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getBillingMessageId() { return billingMessageId; }
    public void setBillingMessageId(String billingMessageId) { this.billingMessageId = billingMessageId; }
    public Integer getPartNumber() { return partNumber; }
    public void setPartNumber(Integer partNumber) { this.partNumber = partNumber; }
    public String getTransactionType() { return transactionType; }
    public void setTransactionType(String transactionType) { this.transactionType = transactionType; }
    public String getProduct() { return product; }
    public void setProduct(String product) { this.product = product; }
    public Integer getUnits() { return units; }
    public void setUnits(Integer units) { this.units = units; }
    public BigDecimal getSalePrice() { return salePrice; }
    public void setSalePrice(BigDecimal salePrice) { this.salePrice = salePrice; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public BigDecimal getSurcharge() { return surcharge; }
    public void setSurcharge(BigDecimal surcharge) { this.surcharge = surcharge; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public String getRawEvent() { return rawEvent; }
    public void setRawEvent(String rawEvent) { this.rawEvent = rawEvent; }
    public String getRawPayload() { return rawPayload; }
    public void setRawPayload(String rawPayload) { this.rawPayload = rawPayload; }
    public String getProcessingStatus() { return processingStatus; }
    public void setProcessingStatus(String processingStatus) { this.processingStatus = processingStatus; }
    public String getProcessingNote() { return processingNote; }
    public void setProcessingNote(String processingNote) { this.processingNote = processingNote; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public String getDedupKey() { return dedupKey; }
    public void setDedupKey(String dedupKey) { this.dedupKey = dedupKey; }
    public Long getDuplicateOf() { return duplicateOf; }
    public void setDuplicateOf(Long duplicateOf) { this.duplicateOf = duplicateOf; }
    public String getReceiverInstance() { return receiverInstance; }
    public void setReceiverInstance(String receiverInstance) { this.receiverInstance = receiverInstance; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
