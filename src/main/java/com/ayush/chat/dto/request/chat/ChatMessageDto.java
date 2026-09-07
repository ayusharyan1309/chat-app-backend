package com.ayush.chat.dto.request.chat;


import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Generated;
import jakarta.validation.constraints.NotBlank;

public class ChatMessageDto {
    private @NotBlank String recipientEmail;
    private @NotBlank String content;
    private String senderUserEmail;
    private Long messageId;
    @JsonProperty("isCrossPlatformMessage")
    private boolean isCrossPlatformMessage;
    @JsonProperty("isAppToWeb")
    private boolean isAppToWeb;

    @Generated
    public String getRecipientEmail() {
        return this.recipientEmail;
    }

    @Generated
    public String getContent() {
        return this.content;
    }

    @Generated
    public String getSenderUserEmail() {
        return this.senderUserEmail;
    }

    @Generated
    public Long getMessageId() {
        return this.messageId;
    }

    @Generated
    public boolean isCrossPlatformMessage() {
        return this.isCrossPlatformMessage;
    }

    @Generated
    public boolean isAppToWeb() {
        return this.isAppToWeb;
    }

    @Generated
    public void setRecipientEmail(String recipientEmail) {
        this.recipientEmail = recipientEmail;
    }

    @Generated
    public void setContent(String content) {
        this.content = content;
    }

    @Generated
    public void setSenderUserEmail(String senderUserEmail) {
        this.senderUserEmail = senderUserEmail;
    }

    @Generated
    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    @JsonProperty("isCrossPlatformMessage")
    @Generated
    public void setCrossPlatformMessage(boolean isCrossPlatformMessage) {
        this.isCrossPlatformMessage = isCrossPlatformMessage;
    }

    @JsonProperty("isAppToWeb")
    @Generated
    public void setAppToWeb(boolean isAppToWeb) {
        this.isAppToWeb = isAppToWeb;
    }

    @Generated
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        } else if (!(o instanceof ChatMessageDto)) {
            return false;
        } else {
            ChatMessageDto other = (ChatMessageDto)o;
            if (!other.canEqual(this)) {
                return false;
            } else if (this.isCrossPlatformMessage() != other.isCrossPlatformMessage()) {
                return false;
            } else if (this.isAppToWeb() != other.isAppToWeb()) {
                return false;
            } else {
                Object this$messageId = this.getMessageId();
                Object other$messageId = other.getMessageId();
                if (this$messageId == null) {
                    if (other$messageId != null) {
                        return false;
                    }
                } else if (!this$messageId.equals(other$messageId)) {
                    return false;
                }

                Object this$recipientEmail = this.getRecipientEmail();
                Object other$recipientEmail = other.getRecipientEmail();
                if (this$recipientEmail == null) {
                    if (other$recipientEmail != null) {
                        return false;
                    }
                } else if (!this$recipientEmail.equals(other$recipientEmail)) {
                    return false;
                }

                Object this$content = this.getContent();
                Object other$content = other.getContent();
                if (this$content == null) {
                    if (other$content != null) {
                        return false;
                    }
                } else if (!this$content.equals(other$content)) {
                    return false;
                }

                Object this$senderUserEmail = this.getSenderUserEmail();
                Object other$senderUserEmail = other.getSenderUserEmail();
                if (this$senderUserEmail == null) {
                    if (other$senderUserEmail != null) {
                        return false;
                    }
                } else if (!this$senderUserEmail.equals(other$senderUserEmail)) {
                    return false;
                }

                return true;
            }
        }
    }

    @Generated
    protected boolean canEqual(Object other) {
        return other instanceof ChatMessageDto;
    }

    @Generated
    public int hashCode() {
        int PRIME = 59;
        int result = 1;
        result = result * 59 + (this.isCrossPlatformMessage() ? 79 : 97);
        result = result * 59 + (this.isAppToWeb() ? 79 : 97);
        Object $messageId = this.getMessageId();
        result = result * 59 + ($messageId == null ? 43 : $messageId.hashCode());
        Object $recipientEmail = this.getRecipientEmail();
        result = result * 59 + ($recipientEmail == null ? 43 : $recipientEmail.hashCode());
        Object $content = this.getContent();
        result = result * 59 + ($content == null ? 43 : $content.hashCode());
        Object $senderUserEmail = this.getSenderUserEmail();
        result = result * 59 + ($senderUserEmail == null ? 43 : $senderUserEmail.hashCode());
        return result;
    }

    @Generated
    public String toString() {
        return "ChatMessageDto(recipientEmail=" + this.getRecipientEmail() + ", content=" + this.getContent() + ", senderUserEmail=" + this.getSenderUserEmail() + ", messageId=" + this.getMessageId() + ", isCrossPlatformMessage=" + this.isCrossPlatformMessage() + ", isAppToWeb=" + this.isAppToWeb() + ")";
    }

    @Generated
    public ChatMessageDto() {
    }

    @Generated
    public ChatMessageDto(String recipientEmail, String content, String senderUserEmail, Long messageId, boolean isCrossPlatformMessage, boolean isAppToWeb) {
        this.recipientEmail = recipientEmail;
        this.content = content;
        this.senderUserEmail = senderUserEmail;
        this.messageId = messageId;
        this.isCrossPlatformMessage = isCrossPlatformMessage;
        this.isAppToWeb = isAppToWeb;
    }
}
