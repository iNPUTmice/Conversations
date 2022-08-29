# Making a backup of Conversations

This tutorial explains how you can backup your Conversations data. 

**WARNING**: Do not use the restore backup feature in an attempt to clone (run simultaneously) an installation. Restoring a backup is only meant for migrations or in case you’ve lost the original device.

1. Make sure that you know the password to your account(s)! You will need it later to decrypt your backup.
2. Deactivate all your account(s): on the chat screen, tap on the three buttons in the upper right, and go to "manage accounts".
3. Go back to Settings, scroll down until you find the option to create a new backup. Tap on that option.
4. Wait, until the notification tells you that the backup is finished.
5. Move the backup to whatever location you feel save with.

Done!

## Further information / troubleshooting
### Unable to decrypt 
This backup method will include your OMEMO keys. Due to forward secrecy you will not be able to recover messages sent and received between creating the backup and restoring it. If you have a server side archive (MAM) those messages will be retrieved but displayed as *unable to decrypt*. For technical reasons you might also lose the first message you either sent or receive after the restore; for each conversation you have. This message will then also show up as *unable to decrypt*, but this will automatically recover itself as long as both participants are on Conversations 2.3.11+. Note that this doesn’t happen if you just transfer to a new phone and no messages have been exchanged between backup and restore.

In the vast, vast majority of cases you won’t have to manually delete OMEMO keys or do anything like that. Conversations only introduced the official backup feature in 2.4.0 after making sure the *OMEMO self healing* mechanism introduced in 2.3.11 works fine.
