-- Separate databases for the control plane and the message store (same server is fine for
-- small installs; split them in production).
CREATE DATABASE chat_control OWNER chat;
CREATE DATABASE chat_messages OWNER chat;
