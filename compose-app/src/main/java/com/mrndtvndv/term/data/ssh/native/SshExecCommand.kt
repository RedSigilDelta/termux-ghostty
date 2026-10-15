package com.mrndtvndv.term.data.ssh.native

// sshd interprets exec requests with the account's shell, which need not understand POSIX scripts.
internal fun posixExecCommand(script: String): String = "/bin/sh -c '" + script.replace("'", "'\\''") + "'"
