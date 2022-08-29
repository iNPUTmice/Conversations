# Migrating to a new device

This tutorial explains how you can transfer your Conversations data from an old to a new device. It assumes that you do not have Conversations installed on your new device, yet. It basically consists of three steps:

1. Make a backup (old device)
2. Move that backup to your new device
3. Import the backup (new device)

**WARNING**: Do not use the restore backup feature in an attempt to clone (run simultaneously) an installation. Restoring a backup is only meant for migrations or in case you’ve lost the original device.

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

Done!

## Further information / troubleshooting
### Unable to decrypt 
This backup method will include your OMEMO keys. Due to forward secrecy you will not be able to recover messages sent and received between creating the backup and restoring it. If you have a server side archive (MAM) those messages will be retrieved but displayed as *unable to decrypt*. For technical reasons you might also lose the first message you either sent or receive after the restore; for each conversation you have. This message will then also show up as *unable to decrypt*, but this will automatically recover itself as long as both participants are on Conversations 2.3.11+. Note that this doesn’t happen if you just transfer to a new phone and no messages have been exchanged between backup and restore.

In the vast, vast majority of cases you won’t have to manually delete OMEMO keys or do anything like that. Conversations only introduced the official backup feature in 2.4.0 after making sure the *OMEMO self healing* mechanism introduced in 2.3.11 works fine.
