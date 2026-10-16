package com.ayush.chat.store.model;

/**
 * Maximum lengths of data-plane fields. Store schemas size their columns from these and the
 * application validates against them, so an over-long value is a 400, never a database error.
 */
public final class FieldLimits {

    public static final int TENANT_ID = 64;
    public static final int USER_ID = 128;
    public static final int ENTITY_ID = 64;
    public static final int CLIENT_MSG_ID = 128;
    public static final int DIRECT_KEY = 300;
    public static final int DISPLAY_NAME = 255;
    public static final int EMAIL = 255;
    public static final int PHONE = 64;
    public static final int URL = 1024;
    public static final int ABOUT = 1024;
    public static final int TITLE = 255;
    public static final int DESCRIPTION = 1024;
    public static final int PREVIEW = 512;
    public static final int EMOJI_CODE_POINTS = 8;
    public static final int DEVICE_TOKEN = 4096;
    public static final int MAX_DEVICES_PER_USER = 10;

    private FieldLimits() {
    }
}
