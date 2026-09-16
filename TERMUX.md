# Termux → GitHub → APK

You do **not** compile the APK in Termux. You push this tree; GitHub compiles it.

```bash
# 1. unzip the clean archive
cd ~/storage/downloads   # or wherever
unzip SpiritScan-Android-clean.zip
cd SpiritScan-Android

# 2. git (install once)
pkg update -y && pkg install git -y

# 3. push
git init
git add .
git commit -m "SpiritScan Android v8.3"
git branch -M main
git remote add origin https://github.com/YOURUSER/YOURREPO.git
git push -u origin main
```

Wait for the **Build APK** workflow (green check). Download the artifact named `SpiritScan-apk`. Transfer it to the phone and install.

If `git push` asks for a token: GitHub → Settings → Developer settings → Personal access tokens (classic) with `repo` scope. Use the token as the password.
