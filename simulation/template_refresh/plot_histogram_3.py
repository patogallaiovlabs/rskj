import matplotlib.pyplot as plt
import numpy as np

antpool_intervals = [0.705312, 6.383799, 8.091436, 0.421265, 29.628538, 5.730737, 4.353257, 9.370898, 3.671257, 10.458857, 26.814065, 4.108632, 30.032519, 1.076249, 0.350357, 0.51899, 15.877013, 2.555206, 29.906507, 6.298369, 0.222948, 8.767303, 30.066706, 4.454736, 26.856767, 24.334307, 5.531615, 28.587304, 30.06702, 7.178451, 0.30481, 6.419902, 10.521822, 8.653541, 12.449472, 0.201326, 13.393142, 2.985683, 3.735065, 0.92098, 15.146793, 6.360832, 29.981809, 30.074424, 10.829015, 14.903468, 6.412895, 3.674356, 0.370697, 1.053845, 30.082634, 3.62231, 30.057634]

viabtc_intervals = [0.335962, 0.24548, 14.476648, 0.356066, 0.602644, 0.246893, 7.149373, 22.193602, 7.772801, 11.844942, 3.13847, 0.495927, 10.488115, 31.024994, 30.1703, 0.267879, 0.296187, 0.403109, 0.367427, 0.491898, 18.243038, 35.835865, 0.785546, 0.282509, 35.963607, 29.915079, 28.029153, 31.989088, 38.524077, 0.448408, 0.755924, 20.266836, 17.540962, 0.265425, 0.425748, 19.38407, 0.365829, 0.57243, 0.249723, 21.19938, 45.004021, 14.998336, 26.532142, 9.761147, 0.287591, 0.239292, 1.131716, 21.985458, 30.005212, 30.300968, 9.891841, 0.959222, 0.235705, 18.612802, 13.512055, 0.278566, 0.229951, 0.614857, 0.222121]

extra_dataset = [14.926791, 29.283653, 20.013076, 13.581777, 31.121843, 29.978932, 0.792374, 1.119979, 18.314708, 36.880511, 44.109857, 49.909241, 55.35826, 16.733733, 38.174482, 20.623435, 51.37585, 46.461247, 9.839425, 9.195283, 0.556642, 1.926246, 47.808239, 45.190696, 31.829041, 0.673654, 1.2367]

# Calculate averages
antpool_avg = np.mean(antpool_intervals)
viabtc_avg = np.mean(viabtc_intervals)
extra_avg = np.mean(extra_dataset)

# Plotting the first histogram with average line
plt.figure(figsize=(8, 6))
plt.hist(antpool_intervals, bins=20, alpha=0.7, color='blue', label='AntPool Intervals')
plt.axvline(antpool_avg, color='red', linestyle='--', linewidth=1.5, label=f'Average: {antpool_avg:.2f}')
plt.title('AntPool Intervals Histogram')
plt.xlabel('Interval (minutes)')
plt.ylabel('Frequency')
plt.grid(axis='y', alpha=0.75)
plt.legend()

# Plotting the second histogram with average line
plt.figure(figsize=(8, 6))
plt.hist(viabtc_intervals, bins=20, alpha=0.7, color='green', label='ViaBTC Intervals')
plt.axvline(viabtc_avg, color='red', linestyle='--', linewidth=1.5, label=f'Average: {viabtc_avg:.2f}')
plt.title('ViaBTC Intervals Histogram')
plt.xlabel('Interval (minutes)')
plt.ylabel('Frequency')
plt.grid(axis='y', alpha=0.75)
plt.legend()

# Plotting the third histogram with average line
plt.figure(figsize=(8, 6))
plt.hist(extra_dataset, bins=20, alpha=0.7, color='red', label='Extra Dataset')
plt.axvline(extra_avg, color='blue', linestyle='--', linewidth=1.5, label=f'Average: {extra_avg:.2f}')
plt.title('Extra Dataset Histogram')
plt.xlabel('Interval (minutes)')
plt.ylabel('Frequency')
plt.grid(axis='y', alpha=0.75)
plt.legend()
plt.show()



