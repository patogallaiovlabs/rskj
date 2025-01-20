import os
import threading
import queue
import matplotlib.pyplot as plt
import matplotlib.dates as mdates
import numpy as np
from log_processor import process_line, difficulties, block_times
from log_utils import tail_log_file

def plot_difficulty_over_time(difficulties, block_times):
    """
    Plot difficulty over time in an independent window
    """
    fig, ax = plt.subplots()
    ax.plot(block_times, difficulties, label='Difficulty')

    # Format the x-axis to show only hours and minutes
    ax.xaxis.set_major_formatter(mdates.DateFormatter('%H:%M'))
    ax.xaxis.set_major_locator(mdates.HourLocator(interval=4))
    fig.autofmt_xdate()

    # Add some text for labels, title and custom x-axis tick labels, etc.
    ax.set_xlabel('Time')
    ax.set_ylabel('Difficulty')
    ax.set_title('Difficulty over time')

    fig.tight_layout()

    plt.show()

if __name__ == "__main__":
    #log_file = "../logs/rsk.log"
    log_file = "samples/rskj-2025-01-15.0.log"
    log_file_path = os.path.abspath(log_file)

    q = queue.Queue()

    # Create a thread to tail the log file
    log_thread = threading.Thread(target=tail_log_file, args=(log_file_path, q))
    log_thread.daemon = True
    log_thread.start()

    # Keep the plot open and process log updates in the main thread
    while True:
        while not q.empty():
            line = q.get()
            process_line(line)
        
        if difficulties and block_times:
            plot_difficulty_over_time(difficulties, block_times)
            print("Plotted difficulty over time")
        
        plt.pause(0.1)