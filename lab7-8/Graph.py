import pandas as pd
import matplotlib.pyplot as plt
import os

def plot_severity_trends(csv_file, output_dir):
    # Read the CSV file
    df = pd.read_csv(csv_file)

    # Extract severity columns
    high_severity = df['High Severity']
    medium_severity = df['Medium Severity']
    low_severity = df['Low Severity']

    # Generate commit numbers
    commit_numbers = range(len(df))

    # Create subplots
    fig, axes = plt.subplots(1, 3, figsize=(15, 5), sharey=True)
    fig.suptitle(f"Severity Trends Over Commits for {os.path.basename(csv_file).split('_')[2].split('.')[0].capitalize()}")

    # Plot High Severity
    axes[0].plot(commit_numbers, high_severity, color='red')
    axes[0].set_title("High Severity")
    axes[0].set_xlabel("Commit Number")
    axes[0].set_ylabel("Vulnerability Count")

    # Plot Medium Severity
    axes[1].plot(commit_numbers, medium_severity, color='orange')
    axes[1].set_title("Medium Severity")
    axes[1].set_xlabel("Commit Number")

    # Plot Low Severity
    axes[2].plot(commit_numbers, low_severity, color='green')
    axes[2].set_title("Low Severity")
    axes[2].set_xlabel("Commit Number")

    # Save the plot
    output_file = os.path.join(output_dir, f"{os.path.basename(csv_file).split('.')[0]}_severity_trends.png")
    plt.savefig(output_file)
    plt.close()
    print(f"Plot saved: {output_file}")

def main():
    # Directory containing the CSV files
    csv_dir = "/home/chirag/Education/STT/lab7-8"
    output_dir = "/home/chirag/Education/STT/lab7-8/plots"

    # Create output directory if it doesn't exist
    os.makedirs(output_dir, exist_ok=True)

    # List of CSV files to process
    csv_files = [
        "bandit_analysis_matplotlib.csv",
        "bandit_analysis_alluxio.csv",
        "bandit_analysis_aiohttp.csv"
    ]

    # Generate plots for each CSV file
    for csv_file in csv_files:
        plot_severity_trends(os.path.join(csv_dir, csv_file), output_dir)

if __name__ == "__main__":
    main()
