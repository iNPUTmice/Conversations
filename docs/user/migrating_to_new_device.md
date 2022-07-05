# Migrating to a new device

This tutorial explains how you can transfer your Conversations data from an old to a new device. It assumes that you do not have Conversations installed on your new device, yet. It basically consists of three steps:

1. Make a backup (old device)
2. Move that backup to your new device
3. Import the backup (new device)

## 1. Make a backup (old device)
1. Make sure that you know the password to your account(s)! You will need it later to decrypt your backup.
2. Deactivate all your account(s): on the chat screen, tap on the three buttons in the upper right, and go to "manage accounts".
3. Go back to Settings, scroll down until you find the option to create a new backup. Tap on that option.
4. Wait, until the notification tells you that the backup is finished.

## 2. Move that backup to your new device
1. Locate the backup. You should find it in your Files, either in *Conversations/Backup* or in *Download/Conversations/Backup*. The file is named after your account (*e.g. kim@example.org*). If you have multiple accounts, you find one file for each.
2. Use your USB cable or bluetooth, your Nextcloud or other cloud storage or pretty much anything you want to copy the backup from the old device to the new device.
3. Remember the location you saved your backup to. For instance, you might want to save them to the *Download* folder.

## 3. Import the backup (new device)
1. Install Conversations on your new device.
2. Open Conversations for the first time.
3. Tap on "Use other server"
4. Tap on the three dot menu in the upper right corner and tap on "Import backup"
5. If your backup files are not listed, tap on the cloud symbol in the upper right corner to choose the files from the where you saved them.
6. Enter your account password to decrypt the backup.
7. Remember to activate your account (head back to "manage accounts", see step 1.2).
8. Check if chats work.

Once confirmed that the new device is running fine you can just uninstall the app from the old device.

Note: The backup only contains your text chats and required encryption keys, all the files need to be transferred separately and put on the new device in the same locations.

Done! If you have more questions regarding backups, you may want to [read this](https://github.com/iNPUTmice/Conversations#how-do-i-backup--move-conversations-to-a-new-device).
