package com.ledger.app;

import android.Manifest;
import android.database.Cursor;
import android.net.Uri;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.getcapacitor.PermissionState;
import org.json.JSONArray;

@CapacitorPlugin(
    name = "SmsPlugin",
    permissions = {
        @Permission(strings = {Manifest.permission.READ_SMS}, alias = "readSms")
    }
)
public class SmsPlugin extends Plugin {

    // Known Indian bank / payment sender IDs (partial match used)
    private static final String[] BANK_SENDERS = {
        "HDFCBK","ICICIB","SBIINB","AXISBK","KOTAKB","YESBK","INDBNK",
        "PNBSMS","CANBNK","UNIONB","IDFCBK","SCBANK","BOIIND","CENTBK",
        "IOBSMS","RBLBNK","IDBIBN","FEDERAL","AUBFIN","DLBNK","KTVBNK",
        "PAYTMB","PHONEPE","GPAY","AMAZON","MOBIKW","JUSPAY","BAJAJFIN",
        "HDFCCC","ICICICC","SBICARD","AXISCC","INDUSCC","KOTAKCC","CITI",
        "AMEXCO","HSBC","SCCARD","YESCC","RBLCC",
    };

    @PluginMethod
    public void readBankSms(PluginCall call) {
        if (getPermissionState("readSms") != PermissionState.GRANTED) {
            requestPermissionForAlias("readSms", call, "smsPermissionCallback");
            return;
        }
        doReadSms(call);
    }

    @PermissionCallback
    private void smsPermissionCallback(PluginCall call) {
        if (getPermissionState("readSms") == PermissionState.GRANTED) {
            doReadSms(call);
        } else {
            call.reject("SMS_PERMISSION_DENIED");
        }
    }

    private void doReadSms(PluginCall call) {
        int maxCount = call.getInt("maxCount", 500);
        JSONArray messages = new JSONArray();

        try {
            Uri smsUri = Uri.parse("content://sms/inbox");
            String[] projection = {"address", "body", "date"};

            // Build WHERE clause for bank senders
            StringBuilder where = new StringBuilder("(");
            for (int i = 0; i < BANK_SENDERS.length; i++) {
                if (i > 0) where.append(" OR ");
                where.append("address LIKE '%").append(BANK_SENDERS[i]).append("%'");
            }
            where.append(")");

            Cursor cursor = getContext().getContentResolver().query(
                smsUri,
                projection,
                where.toString(),
                null,
                "date DESC"
            );

            if (cursor != null) {
                int count = 0;
                while (cursor.moveToNext() && count < maxCount) {
                    String body = cursor.getString(cursor.getColumnIndexOrThrow("body"));
                    if (body != null && isFinancialSms(body)) {
                        messages.put(redactSensitive(body.trim()));
                        count++;
                    }
                }
                cursor.close();
            }
        } catch (Exception e) {
            call.reject("READ_FAILED: " + e.getMessage());
            return;
        }

        JSObject ret = new JSObject();
        ret.put("messages", messages);
        ret.put("count", messages.length());
        call.resolve(ret);
    }

    // Only pass through messages that contain financial keywords
    private boolean isFinancialSms(String body) {
        String lc = body.toLowerCase();
        return (lc.contains("debited") || lc.contains("credited") ||
                lc.contains("rs.") || lc.contains("rs ") || lc.contains("inr") ||
                lc.contains("₹") || lc.contains("upi") || lc.contains("neft") ||
                lc.contains("imps") || lc.contains("sip") || lc.contains("salary") ||
                lc.contains("refund") || lc.contains("cashback") || lc.contains("emi") ||
                lc.contains("a/c") || lc.contains("account")) &&
               !isOtp(body);
    }

    // Exclude pure OTP messages
    private boolean isOtp(String body) {
        String lc = body.toLowerCase();
        boolean hasOtpKeyword = lc.contains("otp") || lc.contains("one time") ||
                                lc.contains("one-time") || lc.contains("verification code") ||
                                lc.contains("auth code");
        // Short messages with OTP keywords and a prominent number = likely OTP
        return hasOtpKeyword && body.length() < 200;
    }

    // Remove sensitive fields before returning to JS layer
    private String redactSensitive(String body) {
        String result = body;

        // Redact OTP numbers (4–8 digit numbers near OTP keywords)
        result = result.replaceAll(
            "(?i)(\\botp\\b|one.?time|verification|auth)[\\s:isare]+\\d{4,8}",
            "$1: [REDACTED]"
        );

        // Redact full card numbers (16-digit sequences)
        result = result.replaceAll(
            "\\b(\\d{4}[\\s\\-]?){3}\\d{4}\\b",
            "XXXX-XXXX-XXXX-XXXX"
        );

        // Redact full UPI VPA (keep only the handle part masked)
        // e.g. john.doe@okaxis -> john***@okaxis
        result = result.replaceAll(
            "\\b([a-zA-Z0-9._%+\\-]{2})[a-zA-Z0-9._%+\\-]*(@[a-zA-Z0-9.\\-]+)\\b",
            "$1***$2"
        );

        // Redact full mobile numbers (10 digits not in an account context)
        result = result.replaceAll(
            "(?<!XXXX)(?<!a\\/c )\\b[6-9]\\d{9}\\b",
            "XXXXXXXXXX"
        );

        return result;
    }
}
