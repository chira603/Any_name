import pandas as pd
import matplotlib.pyplot as plt
import os
from collections import Counter

def aggregate_cwe_data(csv_files, csv_dir):
    cwe_counter = Counter()

    # Process each CSV file
    for csv_file in csv_files:
        df = pd.read_csv(os.path.join(csv_dir, csv_file))

        # Aggregate CWEs from the 'Unique CWEs' column
        for cwe_list in df['Unique CWEs']:
            if pd.notna(cwe_list):
                cwe_counter.update(cwe.split('/')[-1] for cwe in cwe_list.split(';') if cwe.startswith("https://cwe.mitre.org/data/definitions/"))

    return cwe_counter

def plot_cwe_distribution(cwe_counter, output_dir):
    # Sort CWEs by frequency
    sorted_cwes = cwe_counter.most_common()

    # Extract CWE names and counts
    cwe_names = [cwe for cwe, _ in sorted_cwes]
    cwe_counts = [count for _, count in sorted_cwes]

    # Create the bar plot
    plt.figure(figsize=(12, 6))
    plt.barh(cwe_names, cwe_counts, color='skyblue')
    plt.xlabel("Frequency")
    plt.ylabel("CWE")
    plt.title("Most Frequently Introduced CWEs Across Repositories")
    plt.gca().invert_yaxis()  # Invert y-axis for better readability

    # Save the plot
    output_file = os.path.join(output_dir, "cwe_distribution.png")
    plt.tight_layout()
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

    # Aggregate CWE data
    cwe_counter = aggregate_cwe_data(csv_files, csv_dir)

    # Plot CWE distribution
    plot_cwe_distribution(cwe_counter, output_dir)

    # Print the most frequently introduced CWEs
    print("Below are some of the most frequently introduced CWEs by the developers while coding for large codebases:")
    for cwe, count in cwe_counter.most_common(10):  # Top 10 CWEs
        print(f"{cwe}: {count} occurrences")

if __name__ == "__main__":
    main()
