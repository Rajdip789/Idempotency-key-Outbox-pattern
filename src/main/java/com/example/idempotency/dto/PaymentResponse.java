package com.example.idempotency.dto;

public class PaymentResponse {

    private String paymentId;
    private String status;
    private long amountCents;
    private String currency;
    // Lets the client (and us, in this POC) see whether this particular
    // HTTP response was freshly processed or replayed from a stored,
    // already-completed idempotency record. A real API might omit this,
    // but it's the clearest way to demonstrate the behavior.
    private boolean replayed;

    public PaymentResponse() { }

    public PaymentResponse(String paymentId, String status, long amountCents, String currency, boolean replayed) {
        this.paymentId = paymentId;
        this.status = status;
        this.amountCents = amountCents;
        this.currency = currency;
        this.replayed = replayed;
    }

    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getAmountCents() { return amountCents; }
    public void setAmountCents(long amountCents) { this.amountCents = amountCents; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public boolean isReplayed() { return replayed; }
    public void setReplayed(boolean replayed) { this.replayed = replayed; }
}
