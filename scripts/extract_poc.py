import os
import zipfile
import shutil
import sys

workspace_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
output_dir = os.path.join(workspace_dir, 'output')
assets_dir = os.path.join(workspace_dir, 'mobile', 'app', 'src', 'main', 'assets')
temp_dir = os.path.join(workspace_dir, 'mobile', 'build', 'tmp_poc')

bootstrap_zip = os.path.join(output_dir, 'stapk-bootstrap-aarch64.zip')
poc_zip = os.path.join(assets_dir, 'runtime-poc.zip')

if not os.path.exists(bootstrap_zip):
    print(f"未找到 {bootstrap_zip}")
    sys.exit(1)

print("清空并创建临时目录...")
if os.path.exists(temp_dir):
    shutil.rmtree(temp_dir)

os.makedirs(os.path.join(temp_dir, 'runtime', 'bin'))
os.makedirs(os.path.join(temp_dir, 'runtime', 'lib'))
os.makedirs(assets_dir, exist_ok=True)

print("提取 node 及动态库到临时目录...")
deps = []
with zipfile.ZipFile(bootstrap_zip, 'r') as z:
    symlinks = {}
    if 'SYMLINKS.txt' in z.namelist():
        data = z.read('SYMLINKS.txt').decode('utf-8')
        for line in data.split('\n'):
            if '←' in line:
                target, link = line.split('←')
                if link.startswith('./'):
                    link = link[2:]
                symlinks[link] = target
                
    for name in z.namelist():
        if name == 'bin/node':
            z.extract(name, temp_dir)
            extracted_path = os.path.join(temp_dir, name)
            
            # Safely patch RUNPATH using LIEF
            try:
                import lief
            except ImportError:
                print("Missing 'lief' module. Please install it using: pip install lief")
                sys.exit(1)
            try:
                binary = lief.parse(extracted_path)
                if binary is not None:
                    # Remove existing runpaths
                    binary.remove(lief.ELF.DynamicEntry.TAG.RUNPATH)
                    # Add new runpath
                    binary.add(lief.ELF.DynamicEntryRunPath("$ORIGIN/../lib"))
                    binary.write(extracted_path)
            except Exception as e:
                print(f"Failed to patch RUNPATH for node: {e}")
                sys.exit(1)
                
            shutil.move(extracted_path, os.path.join(temp_dir, 'runtime', 'bin', 'node'))
            deps.append("- node")
        elif name.startswith('lib/') and ('.so' in name):
            filename = os.path.basename(name)
            if filename in ['libcrypto.so', 'libssl.so', 'libz.so']:
                continue # skip unversioned .so that conflict with ndk_translation
            # Extract basic required so files
            if any(n in name for n in ['libicu', 'libz', 'libcrypto', 'libssl', 'libcares', 'libnghttp2', 'libbrotli', 'libc++_shared', 'libuv', 'libandroid-support', 'libsqlite3']):
                z.extract(name, temp_dir)
                extracted_path = os.path.join(temp_dir, name)
                
                # Safely patch RUNPATH using LIEF
                try:
                    import lief
                except ImportError:
                    print("Missing 'lief' module. Please install it using: pip install lief")
                    sys.exit(1)
                try:
                    binary = lief.parse(extracted_path)
                    if binary is not None:
                        # Remove existing runpaths
                        binary.remove(lief.ELF.DynamicEntry.TAG.RUNPATH)
                        # Add new runpath
                        binary.add(lief.ELF.DynamicEntryRunPath("$ORIGIN/../lib"))
                        binary.write(extracted_path)
                except Exception as e:
                    print(f"Failed to patch RUNPATH for {filename}: {e}")
                    sys.exit(1)
                    
                dest_path = os.path.join(temp_dir, 'runtime', 'lib', filename)
                shutil.move(extracted_path, dest_path)
                deps.append(f"- {filename}")
                
                # Create copies for symlinks pointing to this file
                for link, target in symlinks.items():
                    if target == filename and link.startswith('lib/'):
                        link_name = os.path.basename(link)
                        if link_name in ['libcrypto.so', 'libssl.so', 'libz.so']:
                            continue  # Skip unversioned .so symlinks to avoid breaking ndk_translation on x86_64 emulators
                        link_path = os.path.join(temp_dir, 'runtime', 'lib', link_name)
                        shutil.copy(dest_path, link_path)
                        deps.append(f"- {link_name} (symlink to {filename})")

print("生成依赖清单...")
specs_dir = os.path.join(workspace_dir, 'docs', 'superpowers', 'specs')
os.makedirs(specs_dir, exist_ok=True)
dependency_list = os.path.join(specs_dir, 'runtime-dependencies.md')

with open(dependency_list, 'w', encoding='utf-8') as f:
    f.write("# Runtime POC 依赖清单\n\n")
    for dep in sorted(set(deps)):
        f.write(dep + "\n")

print("打包 runtime-poc.zip...")
with zipfile.ZipFile(poc_zip, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(os.path.join(temp_dir, 'runtime')):
        for file in files:
            file_path = os.path.join(root, file)
            arcname = os.path.relpath(file_path, temp_dir)
            z.write(file_path, arcname)

print("清理临时文件...")
shutil.rmtree(temp_dir)
print(f"完成！生成的 ZIP 位于：{poc_zip}")
