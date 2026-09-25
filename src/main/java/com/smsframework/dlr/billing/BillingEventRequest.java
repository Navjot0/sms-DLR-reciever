package com.smsframework.dlr.billing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One entry of a billing DLR's "events" array:
 * <pre>
 * {"transaction_type":"debit","message_id":"9b1b0309-...:1","product":"SMS Transactional","units":1,
 *  "sale_price":1,"currency":"INR","surcharge":0,"total_amount":1}
 * </pre>
 * Required: message_id, transaction_type. Numeric fields are optional but must be numbers when present.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BillingEventRequest {

    @NotBlank(message = "message_id is missing")
    @Size(max = 255, message = "message_id exceeds 255 characters")
    @JsonProperty("message_id")
    private String messageId;

    @NotBlank(message = "transaction_type is missing")
    @Size(max = 50, message = "transaction_type exceeds 50 characters")
    @JsonProperty("transaction_type")
    private String transactionType;

    @Size(max = 255, message = "product exceeds 255 characters")
    @JsonProperty("product")
    private String product;

    @Size(max = 10, message = "currency exceeds 10 characters")
    @JsonProperty("currency")
    private String currency;

    // Kept as text so "1", 1 and 1.0 are all accepted; parsed (strictly) by the adapter.
    @Size(max = 40, message = "units exceeds 40 characters")
    @JsonProperty("units")
    private String units;

    @Size(max = 40, message = "sale_price exceeds 40 characters")
    @JsonProperty("sale_price")
    private String salePrice;

    @Size(max = 40, message = "surcharge exceeds 40 characters")
    @JsonProperty("surcharge")
    private String surcharge;

    @Size(max = 40, message = "total_amount exceeds 40 characters")
    @JsonProperty("total_amount")
    private String totalAmount;

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getTransactionType() { return transactionType; }
    public void setTransactionType(String transactionType) { this.transactionType = transactionType; }
    public String getProduct() { return product; }
    public void setProduct(String product) { this.product = product; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getUnits() { return units; }
    public void setUnits(String units) { this.units = units; }
    public String getSalePrice() { return salePrice; }
    public void setSalePrice(String salePrice) { this.salePrice = salePrice; }
    public String getSurcharge() { return surcharge; }
    public void setSurcharge(String surcharge) { this.surcharge = surcharge; }
    public String getTotalAmount() { return totalAmount; }
    public void setTotalAmount(String totalAmount) { this.totalAmount = totalAmount; }
}
