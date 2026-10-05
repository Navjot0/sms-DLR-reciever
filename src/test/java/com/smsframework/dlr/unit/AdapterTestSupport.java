package com.smsframework.dlr.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smsframework.dlr.adapter.DefaultSmsDlrAdapter;
import com.smsframework.dlr.adapter.WebEngageDlrAdapter;
import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.service.StatusNormalizer;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

final class AdapterTestSupport {

    static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    static final DlrProperties PROPS = new DlrProperties();
    static final StatusNormalizer NORMALIZER = new StatusNormalizer(PROPS);

    private AdapterTestSupport() {
    }

    static DefaultSmsDlrAdapter defaultAdapter() {
        return new DefaultSmsDlrAdapter(MAPPER, VALIDATOR, NORMALIZER, PROPS);
    }

    static WebEngageDlrAdapter webEngageAdapter() {
        return new WebEngageDlrAdapter(MAPPER, VALIDATOR, NORMALIZER, PROPS);
    }

    static com.smsframework.dlr.meta.MetaWhatsAppDlrAdapter metaAdapter() {
        DlrProperties p = new DlrProperties();
        p.getMeta().setRequireMessageId(false);      // wamid fallback, for payloads without message_id
        return new com.smsframework.dlr.meta.MetaWhatsAppDlrAdapter(NORMALIZER, MAPPER, p);
    }

    static com.smsframework.dlr.meta.MetaWhatsAppDlrAdapter strictMetaAdapter() {
        return new com.smsframework.dlr.meta.MetaWhatsAppDlrAdapter(NORMALIZER, MAPPER, PROPS);
    }

    static com.smsframework.dlr.rcs.RcsDlrAdapter rcsAdapter() {
        return new com.smsframework.dlr.rcs.RcsDlrAdapter(NORMALIZER, MAPPER, PROPS);
    }

    static com.smsframework.dlr.email.EmailDlrAdapter emailAdapter() {
        return new com.smsframework.dlr.email.EmailDlrAdapter(NORMALIZER, MAPPER, PROPS);
    }

    static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
