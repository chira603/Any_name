def generate_matrix(size, is_float=False):
    """Generates an NxN matrix with random values."""
    matrix = []
    for i in range(size):
        row = []
        for j in range(size):
            if is_float:
                value = (i + j + 1) * 0.5  # Simple float generation
            else:
                value = (i + j + 1)  # Simple integer generation
            row.append(value)
        matrix.append(row)
    return matrix

def multiply_matrices(matrix_a, matrix_b):
    """Performs matrix multiplication of two NxN matrices."""
    size = len(matrix_a)
    result = [[0 for _ in range(size)] for _ in range(size)]

    for i in range(size):
        for j in range(size):
            for k in range(size):
                result[i][j] += matrix_a[i][k] * matrix_b[k][j]

    return result

def print_matrix(matrix):
    """Prints a matrix in a readable format."""
    for row in matrix:
        print("\t".join(f"{val:.2f}" if isinstance(val, float) else f"{val}" for val in row))
    print()

# Input matrix size and type
N = int(input("Enter the matrix size (e.g., 3, 4, 5): "))
use_float = input("Use float values? (yes/no): ").strip().lower() == "yes"

# Generate matrices
matrix_a = generate_matrix(N, is_float=use_float)
matrix_b = generate_matrix(N, is_float=use_float)

# Print matrices
print("Matrix A:")
print_matrix(matrix_a)

print("Matrix B:")
print_matrix(matrix_b)

# Multiply matrices
result_matrix = multiply_matrices(matrix_a, matrix_b)

# Print result
print("Result of Matrix Multiplication:")
print_matrix(result_matrix)
