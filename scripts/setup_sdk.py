import os
import sys
import urllib.request
import zipfile

def install_cmdline_tools():
    url = 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip'
    sdk_dir = r'D:\Programming\android\sdk'
    dest = os.path.join(sdk_dir, 'cmdline-tools')
    latest_target = os.path.join(dest, 'latest')

    if os.path.exists(os.path.join(latest_target, 'bin', 'sdkmanager.bat')):
        print("sdkmanager already installed at", latest_target)
        return

    os.makedirs(dest, exist_ok=True)
    zip_path = os.path.join(dest, 'cmdline.zip')

    print(f"Downloading Android command-line tools (148MB)...")
    def reporthook(count, block_size, total_size):
        percent = int(count * block_size * 100 / total_size)
        sys.stdout.write(f"\rDownloading: {percent}% ({count * block_size // 1048576}MB / {total_size // 1048576}MB)")
        sys.stdout.flush()

    urllib.request.urlretrieve(url, zip_path, reporthook)
    print("\nExtracting...")

    with zipfile.ZipFile(zip_path, 'r') as z:
        z.extractall(dest)

    if os.path.exists(zip_path):
        os.remove(zip_path)

    extracted_dir = os.path.join(dest, 'cmdline-tools')
    if os.path.exists(latest_target):
        import shutil
        shutil.rmtree(latest_target)

    if os.path.exists(extracted_dir):
        os.rename(extracted_dir, latest_target)

    print("Successfully installed cmdline-tools to:", latest_target)

if __name__ == '__main__':
    install_cmdline_tools()
