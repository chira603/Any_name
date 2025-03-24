#!/bin/bash

OUTPUT_FILE="bandit_analysis.csv"

# Initialize the CSV file with headers
echo "Commit,High Confidence,Medium Confidence,Low Confidence,High Severity,Medium Severity,Low Severity,Unique CWEs" > $OUTPUT_FILE

# Loop through each commit hash in commits.txt
while read commit; do
    echo "Processing commit: $commit"
    
    # Checkout the specific commit
    git checkout "$commit" --quiet

    # Run Bandit and capture its output
    bandit_raw=$(bandit -r . --exclude .git -f json 2>&1)
    
    # Extract the JSON portion from Bandit's output
    bandit_output=$(echo "$bandit_raw" | sed -n '/^{/,$p')

    # Validate the JSON output; skip the commit if invalid
    if ! echo "$bandit_output" | jq empty 2>/dev/null; then
        echo "Warning: Invalid JSON output for commit $commit. Skipping..."
        continue
    fi

    # Extract counts for confidence levels
    high_conf=$(echo "$bandit_output" | jq '[.results[] | select(.issue_confidence == "HIGH")] | length')
    med_conf=$(echo "$bandit_output" | jq '[.results[] | select(.issue_confidence == "MEDIUM")] | length')
    low_conf=$(echo "$bandit_output" | jq '[.results[] | select(.issue_confidence == "LOW")] | length')

    # Extract counts for severity levels
    high_sev=$(echo "$bandit_output" | jq '[.results[] | select(.issue_severity == "HIGH")] | length')
    med_sev=$(echo "$bandit_output" | jq '[.results[] | select(.issue_severity == "MEDIUM")] | length')
    low_sev=$(echo "$bandit_output" | jq '[.results[] | select(.issue_severity == "LOW")] | length')

    # Extract unique CWE IDs and construct their URLs
    unique_cwe_ids=$(echo "$bandit_output" | jq -r '[.results[].issue_cwe.id // "null"] | unique | .[]')
    cwe_links=""

    for id in $unique_cwe_ids; do
        if [ "$id" = "null" ]; then
            link="https://cwe.mitre.org/"
        else
            link="https://cwe.mitre.org/data/definitions/${id}.html"
        fi

        if [ -z "$cwe_links" ]; then
            cwe_links="$link"
        else
            cwe_links="$cwe_links;$link"
        fi
    done

    # Append the results to the CSV file
    echo "$commit,$high_conf,$med_conf,$low_conf,$high_sev,$med_sev,$low_sev,$cwe_links" >> $OUTPUT_FILE

done < commits.txt

# Return to the main branch after processing
git checkout main
echo "Analysis complete. Results saved in $OUTPUT_FILE."

