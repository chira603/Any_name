import os
import json
import matplotlib.pyplot as plt

RESULTS_DIR = "parallel_results"
JSON_RESULTS_FILE = os.path.join(RESULTS_DIR, "parallel_test_results.json")
PLOT_FILE = os.path.join(RESULTS_DIR, "speedup_plot.png")

def generate_data_table():
    """Generate and print the data table from the JSON results."""
    if not os.path.exists(JSON_RESULTS_FILE):
        print(f"Error: Results file {JSON_RESULTS_FILE} not found!")
        return

    with open(JSON_RESULTS_FILE, "r") as f:
        results = json.load(f)

    # Use the first configuration as the baseline for speedup calculation
    baseline_time = None
    table = []

    for config, data in results.items():
        avg_time = data["average_execution_time"]
        if baseline_time is None:
            baseline_time = avg_time  # Set the first configuration as the baseline

        speedup = round(baseline_time / avg_time, 2)
        failure_counts = [len(failures) for failures in data["failures_per_repetition"]]
        failing_tests = ", ".join(data["flaky_tests"].keys()) if data["flaky_tests"] else "None"

        table.append({
            "Configuration": config,
            "Average time(sec)": round(avg_time, 2),
            "Speedup": speedup,
            "Failure Counts per Run": failure_counts,
            "Failing Tests": failing_tests
        })

    # Print the table
    print("\nConfiguration | Average time(sec) | Speedup | Failure Counts per Run | Failing Tests")
    print("-" * 90)
    for row in table:
        print(f"{row['Configuration']} | {row['Average time(sec)']} | {row['Speedup']} | {row['Failure Counts per Run']} | {row['Failing Tests']}")

def plot_speedup():
    """Plot the speedup graph."""
    if not os.path.exists(JSON_RESULTS_FILE):
        print(f"Error: Results file {JSON_RESULTS_FILE} not found!")
        return

    with open(JSON_RESULTS_FILE, "r") as f:
        results = json.load(f)

    # Extract data for plotting
    configurations = []
    speedups = []

    # Use the first configuration as the baseline for speedup calculation
    baseline_time = None

    for config, data in results.items():
        avg_time = data["average_execution_time"]
        if baseline_time is None:
            baseline_time = avg_time  # Set the first configuration as the baseline

        configurations.append(config)
        speedups.append(baseline_time / avg_time)

    # Plot the speedup graph
    plt.figure(figsize=(10, 6))
    plt.barh(configurations, speedups, color="skyblue")
    plt.xlabel("Speedup")
    plt.ylabel("Configuration")
    plt.title("Speedup by Configuration")
    plt.tight_layout()

    # Save the plot
    plt.savefig(PLOT_FILE)
    print(f"Speedup plot saved to {PLOT_FILE}")

if __name__ == "__main__":
    generate_data_table()
    plot_speedup()