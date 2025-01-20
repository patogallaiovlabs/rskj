import subprocess

def tail_log_file(file_path, q):
    process = subprocess.Popen(['tail', '-1000000000000000F', file_path], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    while True:
        line = process.stdout.readline()
        if line:
            q.put(line)