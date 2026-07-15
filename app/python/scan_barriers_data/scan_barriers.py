
import math
from numpy.typing import NDArray
import pandas as pd
import numpy as np
from fastdtw import fastdtw
from scipy.spatial.distance import euclidean
from sklearn.cluster import DBSCAN
import matplotlib.pyplot as plt

def generate_mock_gps_data():
    # ---------------------------------------------------------
    # 1. MOCK DATA SETUP (Replace this with your actual data)
    # ---------------------------------------------------------
    # Assuming you have a list of dataframes, one for each 30-second event.
    # We will create fake end-points for 3 different barriers.
    np.random.seed(42)
    barrier_1 = np.random.normal(loc=[44.4268, 26.1025], scale=[0.0001, 0.0001], size=(40, 2)) # Bucharest
    barrier_2 = np.random.normal(loc=[46.7712, 23.5901], scale=[0.0001, 0.0001], size=(35, 2)) # Cluj
    barrier_3 = np.random.normal(loc=[45.7489, 21.2087], scale=[0.0001, 0.0001], size=(30, 2)) # Timisoara
    noise = np.random.uniform(low=[44.0, 21.0], high=[47.0, 27.0], size=(5, 2)) # Random trigger noise

    # Combine into a single array representing the LAST row of your 110 time series
    # Format: [[lat, lng], [lat, lng], ...]
    final_gps_points = np.vstack([barrier_1, barrier_2, barrier_3, noise])
    
    return final_gps_points

def haversine_clustering_of_gps_points(final_gps_points:NDArray[np.float64]):
    

    # ---------------------------------------------------------
    # 2. PREPARING FOR DBSCAN (The crucial Haversine math)
    # ---------------------------------------------------------
    # Earth's radius in kilometers
    EARTH_RADIUS_KM = 6371.0088

    # Set your clustering threshold (e.g., group points within 15 meters of each other)
    MAX_DISTANCE_METERS = 4
    max_distance_km = MAX_DISTANCE_METERS / 1000.0

    # Convert epsilon (eps) to radians for scikit-learn's Haversine metric
    epsilon_radians = max_distance_km / EARTH_RADIUS_KM

    # Convert actual GPS coordinates (degrees) to radians
    coordinates_radians = np.radians(final_gps_points)

    # ---------------------------------------------------------
    # 3. RUNNING DBSCAN
    # ---------------------------------------------------------
    # min_samples=3 means at least 3 triggers must happen in the same spot to be called a "barrier"
    dbscan = DBSCAN(eps=epsilon_radians, min_samples=3, metric='haversine', algorithm='ball_tree')
    cluster_labels = dbscan.fit_predict(coordinates_radians)

    # Add the labels back to a readable dataframe
    df = pd.DataFrame(final_gps_points, columns=['lat', 'lng'])
    df['cluster_id'] = cluster_labels

    # ---------------------------------------------------------
    # 4. EXTRACT BARRIER EXACT LOCATIONS (Centroids)
    # ---------------------------------------------------------
    # In DBSCAN, a cluster_id of -1 means "Noise" (an isolated trigger event)
    valid_clusters = df[df['cluster_id'] != -1]

    # Calculate the exact center (mean) of each barrier cluster
    barrier_locations = valid_clusters.groupby('cluster_id')[['lat', 'lng']].mean().reset_index()

    print("--- Identified Barrier Locations ---")
    for index, row in barrier_locations.iterrows():
        print(f"Barrier {int(row['cluster_id'])}: Lat {row['lat']:.6f}, Lng {row['lng']:.6f}")

    print(f"\nIgnored {len(df[df['cluster_id'] == -1])} noisy/isolated trigger events.")

    # ---------------------------------------------------------
    # 5. VISUALIZATION (Optional)
    # ---------------------------------------------------------
    plt.figure(figsize=(8, 6))

    # Plot clusters
    unique_labels = set(cluster_labels)
    colors = [plt.cm.Spectral(each) for each in np.linspace(0, 1, len(unique_labels))]

    for k, col in zip(unique_labels, colors):
        if k == -1:
            col = [0, 0, 0, 1]  # Black for noise
            label = 'Noise'
        else:
            label = f'Barrier {k}'

        class_member_mask = (cluster_labels == k)
        xy = final_gps_points[class_member_mask]
        plt.plot(xy[:, 1], xy[:, 0], 'o', markerfacecolor=tuple(col), 
                markeredgecolor='k', markersize=8, label=label)

    # Plot the calculated barrier centers
    plt.scatter(barrier_locations['lng'], barrier_locations['lat'], 
                c='red', marker='x', s=200, linewidths=3, label='Calculated Center')

    plt.title('DBSCAN Clustering of Barrier Lift Triggers')
    plt.xlabel('Longitude')
    plt.ylabel('Latitude')
    plt.legend()
    plt.grid(True, linestyle='--', alpha=0.6)
    plt.savefig('out/barrier_clusters.png') # saving the plot

    plt.show()

def load_and_filter_gps_data(file_path: str):
    # Load your actual GPS data from a CSV file
    df = pd.read_csv(file_path)

    # Sort by time first just in case
    df = df.sort_values(by=['SessionId', 'Time'])

    # Grab the last row of every sequence
    final_points_df = df.groupby('SessionId').last()[['Lat', 'Lng', 'Accuracy']]

    # some filtering
    final_points_df = final_points_df[final_points_df['Accuracy'] < 10]  # remove rows where accuracy is to low (higher value means less accurate)
    print(f"Filtered GPS data: {len(final_points_df)} rows remaining after accuracy filter.")

    # Grab the last row of every sequence
    final_points_df = final_points_df[['Lat', 'Lng']]

    # Convert to numpy array for DBSCAN
    final_gps_points = final_points_df.values

    return final_gps_points

# haversine_clustering_of_gps_points( generate_mock_gps_data() )

# haversine_clustering_of_gps_points( load_and_filter_gps_data('./in/motion_data_1783605149449.csv') )

# haversine_clustering_of_gps_points( load_and_filter_gps_data('./in/motion_data_1783605206407.csv') )

# haversine_clustering_of_gps_points( load_and_filter_gps_data('./in/motion_data_1783938922434.csv') )


#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
#~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

# ==========================================
# 1. SPATIAL MATH: LAT/LNG TO VECTORS
# ==========================================

def calculate_haversine_distance(lat1, lon1, lat2, lon2):
    """Calculates the great-circle distance between two GPS points in METERS."""
    R = 6371000.0
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)
    
    a = (math.sin(delta_phi / 2.0) ** 2 + 
         math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda / 2.0) ** 2)
    c = 2.0 * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))
    return R * c

def calculate_bearing(lat1, lon1, lat2, lon2):
    """Calculates the initial compass bearing from Point 1 to Point 2."""
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    delta_lambda = math.radians(lon2 - lon1)
    
    y = math.sin(delta_lambda) * math.cos(phi2)
    x = (math.cos(phi1) * math.sin(phi2) - 
         math.sin(phi1) * math.cos(phi2) * math.cos(delta_lambda))
    
    theta = math.atan2(y, x)
    return (math.degrees(theta) + 360.0) % 360.0

def get_relative_vector(current_lat, current_lon, current_gps_heading, barrier_lat, barrier_lon):
    """
    Translates raw GPS coordinates into relative DTW features:
    [distance_to_barrier_meters, delta_heading_degrees]
    """
    ideal_bearing = calculate_bearing(current_lat, current_lon, barrier_lat, barrier_lon)
    
    delta_heading = abs(current_gps_heading - ideal_bearing)
    if delta_heading > 180.0:
        delta_heading = 360.0 - delta_heading
        
    return delta_heading


def calculate_haversine_distance_lla(lat1, lon1, alt1, lat2, lon2, alt2):
    """Calculates the great-circle distance between two GPS points in METERS."""
    R = 6371000.0
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)
    
    a = (math.sin(delta_phi / 2.0) ** 2 + 
         math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda / 2.0) ** 2)
    c = 2.0 * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))
    
    horizontal_distance = R * c
    vertical_distance = alt2 - alt1
    
    return math.sqrt(horizontal_distance**2 + vertical_distance**2)


# ==========================================
# 2. DATA PREPROCESSING
# ==========================================

def process_raw_telemetry_independent(raw_runs):
    """
    Processes raw runs into the 4D vector: [Distance, Turn_Rate, Speed, Accel].
    The distance is calculated dynamically against the final point of EACH run, 
    making the spatial feature purely self-referential to that specific trajectory.
    """
    processed_runs = []
    
    for run in raw_runs:
        processed_run = []
        num_points = len(run)
        previous_heading = None
        
        # Extract the target (barrier) strictly from the last point of THIS run
        run_target_lat = run[-1][0]
        run_target_lon = run[-1][1]
        
        for i in range(num_points):
            lat, lon, speed, accel = run[i]
            
            # 1. Calculate Distance to THIS run's specific end point
            distance = calculate_haversine_distance(lat, lon, run_target_lat, run_target_lon)
            
            # 2. Infer Current Heading from Trajectory
            if i > 0:
                prev_lat, prev_lon = run[i-1][0], run[i-1][1]
                current_heading = calculate_bearing(prev_lat, prev_lon, lat, lon)
            else:
                next_lat, next_lon = run[i+1][0], run[i+1][1]
                current_heading = calculate_bearing(lat, lon, next_lat, next_lon)
                
            # 3. Calculate Turn Rate
            if previous_heading is None:
                turn_rate = 0.0
            else:
                turn_rate = current_heading - previous_heading
                
                # Normalize between -180 and +180 degrees
                if turn_rate > 180.0:
                    turn_rate -= 360.0
                elif turn_rate < -180.0:
                    turn_rate += 360.0
            
            # 4. Construct the Final 4D Vector
            processed_run.append([distance, turn_rate, speed, accel])
            previous_heading = current_heading
            
        processed_runs.append(np.array(processed_run))
        
    return processed_runs

def process_raw_telemetry(raw_runs, barrier_lat, barrier_lon):
    """
    Takes raw runs formatted as [Lat, Lng, Speed, Accel] and 
    infers the heading dynamically to create DTW-ready arrays:
    [Distance, Delta_Heading, Speed, Accel].
    """
    processed_runs = []
    
    for run in raw_runs:
        processed_run = []
        num_points = len(run)
        
        for i in range(num_points):
            lat, lon, speed, accel = run[i]
            
            # Infer heading from the trajectory
            if i > 0:
                # Normal case: Calculate bearing from previous point to current point
                prev_lat, prev_lon = run[i-1][0], run[i-1][1]
                inferred_heading = calculate_bearing(prev_lat, prev_lon, lat, lon)
            else:
                # Edge case (First point): Calculate bearing from current point to next point
                next_lat, next_lon = run[i+1][0], run[i+1][1]
                inferred_heading = calculate_bearing(lat, lon, next_lat, next_lon)

            dist = calculate_haversine_distance(lat, lon, barrier_lat, barrier_lon)    
            delta_h = get_relative_vector(lat, lon, inferred_heading, barrier_lat, barrier_lon)

            processed_run.append([dist, delta_h, speed, accel])
            
        processed_runs.append(np.array(processed_run))
        
    return processed_runs

def normalize_runs(runs:list[list[NDArray[np.float64]]]) -> list[list[NDArray[np.float64]]]:
    """Normalizes the nD features so they scale between 0 and 1."""
    all_data = np.vstack(runs)
    min_vals = all_data.min(axis=0)
    max_vals = all_data.max(axis=0)
    
    range_vals = max_vals - min_vals
    range_vals[range_vals == 0] = 1 # Avoid division by zero
    
    return [(run - min_vals) / range_vals for run in runs]


# ==========================================
# 3. CLUSTERING & MEDOID EXTRACTION
# ==========================================

def build_distance_matrix(runs):
    """Calculates the N x N pairwise DTW distance matrix."""
    N = len(runs)
    matrix = np.zeros((N, N))
    
    print(f"Building {N}x{N} DTW Distance Matrix...")
    for i in range(N):
        for j in range(i + 1, N):
            distance, _ = fastdtw(runs[i], runs[j], dist=euclidean)
            matrix[i, j] = distance
            matrix[j, i] = distance
            
    return matrix

def find_medoids(distance_matrix, labels):
    """Identifies the Medoid (most central run) for each discovered cluster."""
    medoids = {}
    unique_clusters = set(labels)
    
    for cluster_id in unique_clusters:
        if cluster_id == -1:
            continue # Skip noise/anomalies
            
        indices = np.where(labels == cluster_id)[0]
        sub_matrix = distance_matrix[np.ix_(indices, indices)]
        
        sum_distances = sub_matrix.sum(axis=1)
        medoid_index_in_sub = np.argmin(sum_distances)
        
        medoids[cluster_id] = indices[medoid_index_in_sub]
        
    return medoids


# ==========================================
# 4. EXECUTION PIPELINE
# ==========================================

def pipeline_demo():
    BARRIER_LAT = 44.428000
    BARRIER_LON = 26.104000
    
    # --- MOCK DATA GENERATION (UPDATED) ---
    # Now simulating only 4 features: [Latitude, Longitude, Speed, Acceleration]
    N_RUNS = 40
    TIME_STEPS = 30
    RAW_FEATURES = 4
    
    print("Loading raw GPS telemetry (without heading)...")
    raw_dataset = [np.random.uniform(low=0, high=50, size=(TIME_STEPS, RAW_FEATURES)) for _ in range(N_RUNS)]
    
    # Step 1: Process Telemetry (This now infers heading internally)
    print("Inferring headings and translating into relative vectors...")
    relative_runs = process_raw_telemetry(raw_dataset, BARRIER_LAT, BARRIER_LON)
    
    # Step 2: Normalize
    print("Normalizing features...")
    normalized_runs = normalize_runs(relative_runs)
    
    # Step 3: Compute DTW
    distance_matrix = build_distance_matrix(normalized_runs)
    
    # Step 4: Clustering
    print("Executing DBSCAN clustering...")
    dbscan = DBSCAN(eps=5.0, min_samples=3, metric="precomputed")
    labels = dbscan.fit_predict(distance_matrix)
    
    print("\n--- Clustering Results ---")
    print(f"Total Runs Processed: {len(labels)}")
    print(f"Cluster Assignments: {labels}")
    
    # Step 5: Extract Templates
    medoids = find_medoids(distance_matrix, labels)
    
    for cluster_id, medoid_idx in medoids.items():
        print(f"\n✅ Profile {cluster_id} Golden Template found at index: {medoid_idx}")


def pipeline_step0_filter_samples(df: pd.DataFrame, T1: float = 20, T1a: int = 30, T1b: float = 10) -> pd.DataFrame:
    
    # drop data samples that have a GPS accuracy worse than T1 (20 meters)
    df = df[df['Accuracy'] < T1]

    # drop time series that don't have T1a (30) samples (1Hz for 30 seconds)
    df = df.groupby('SessionId').filter(lambda x: len(x) == T1a)

    #   sort by session id & time 
    df = df.sort_values(by=['SessionId', 'Time'])
    # drop time series that have a GPS accuracy worse than T1b (10 meters) for the last sample (the last sample is used to compute the barrier position)
    last_samples = df.groupby('SessionId').last().reset_index()
    df = df[df['SessionId'].isin(last_samples[last_samples['Accuracy'] < T1b]['SessionId'])]

    return df

def pipeline_step0_raw_to_relative_telemetry_independent(raw_runs):
    """
    Input: raw_runs is a list of runs, where each run is a list of 1+6D vectors: [SessionId, Time, Lat, Lng, Alt, Speed, Accel]
    Processes raw runs into the 1+4D vector: [SessionId, Distance, Turn_Rate, Speed, Accel].
    The distance is calculated dynamically against the final point of EACH run, 
    making the spatial feature purely self-referential to that specific trajectory.
    """
    processed_runs = list[list[NDArray[np.float64]]]()
    
    for run in raw_runs:
        processed_run = []

        # order run by time just in case
        run = sorted(run, key=lambda x: x[1])  # Sort by Time

        num_points = len(run)
        
        # Extract the target (barrier) strictly from the last point of THIS run
        run_target_lat = run[-1][2]
        run_target_lon = run[-1][3]
        run_target_alt = run[-1][4]
        
        for i in range(num_points):
            session_id, time, lat, lon, alt, speed, accel = run[i]
            
            # 1. Calculate Distance to THIS run's specific end point
            distance = calculate_haversine_distance_lla(lat, lon, alt, run_target_lat, run_target_lon, run_target_alt)
            # distance = calculate_haversine_distance(lat, lon, run_target_lat, run_target_lon)
            
            # 2. Infer Current Heading from Trajectory
            if i > 0:
                prev_lat, prev_lon = run[i-1][2], run[i-1][3]
                current_heading = calculate_bearing(prev_lat, prev_lon, lat, lon)
            else:
                next_lat, next_lon = run[i+1][2], run[i+1][3]
                current_heading = calculate_bearing(lat, lon, next_lat, next_lon)
                
            # 3. Calculate delta heading
            delta_h = get_relative_vector(lat, lon, current_heading, run_target_lat, run_target_lon)
            
            # 4. Construct the Final 1+4D Vector
            processed_run.append( [session_id, distance, delta_h, speed, accel] )
            
        # processed_runs.append(np.array(processed_run))
        processed_runs.append( processed_run )
        
    return processed_runs


def pipeline_step0():

    do_plots = True

    # file name
    file_path = './in/motion_data_1784134284284.csv' # motion_data_1784134284284 # motion_data_1783605149449 # motion_data_1783605206407 # motion_data_1783938922434


    # ==========================================
    # load data samples from file. 
    #   samples are collected at 1Hz, forming a time series of 30 samples for each session as a 1+7D vectors: [SessionId, Time, Accuracy, Lat, Lng, Alt, Speed, Accel]
    df = pd.read_csv(file_path)
    # sort by session id & time 
    df = df.sort_values(by=['SessionId', 'Time'])

    print(f"Loaded {len(df)} rows of raw telemetry data from {file_path}.")


    # ==========================================
    # data cleaning and filtering: 
    #   - drop data samples that have a GPS accuracy worse than T1 (20 meters)
    #   - drop time series that don't have T1a (30) samples (1Hz for 30 seconds)
    #   - drop time series that have a GPS accuracy worse than T1b (10 meters) for the last sample (the last sample is used to compute the barrier position)
    T1 = 20.0
    T1a = 30
    T1b = 10.0
    df = pipeline_step0_filter_samples(df, T1, T1a, T1b)

    print(f"Resulted in {len(df)} rows of raw telemetry data after filtering.")


    # ==========================================
    # convert data frame to list of runs (time series) grouped by SessionId, each run is a list of 1+6D vectors: [SessionId, Time, Lat, Lng, Alt, Speed, Accel]
    raw_runs = [group[['SessionId', 'Time', 'Lat', 'Lng', 'Alt', 'Speed', 'Accel']].values.tolist() for _, group in df.groupby('SessionId')]
    if False:
        print(f"Converted to {len(raw_runs)} runs (time series) after grouping by SessionId.")
        print(f"Each run has {len(raw_runs[0])} samples (should be {T1a}).")
        #   print the first & second columns for some runs to verify
        for i, run in enumerate(raw_runs[:3]):  # Just show the first
            for j, sample in enumerate(run):
                print(f"Run {i}: sample {j} SessionId: {sample[0]}, Time: {sample[1]}")
    # dump to csv for later use
    if True:
        dump_csv = pd.DataFrame(np.vstack(raw_runs), columns=['SessionId', 'Time', 'Lat', 'Lng', 'Alt', 'Speed', 'Accel'])
        dump_csv.to_csv('out/barriers_raw_runs.csv', index=False)
    # plot raw_runs
    if do_plots:
        for i, run in enumerate(raw_runs):  
            run = sorted(run, key=lambda x: x[1])  # Sort by Time
            latitudes = [sample[2] for sample in run]
            longitudes = [sample[3] for sample in run]
            plt.plot(longitudes, latitudes, marker='o', label=f'Run {i}')
        plt.title('Raw Trajectories for raw runs')
        plt.xlabel('Longitude')
        plt.ylabel('Latitude')
        plt.legend()
        plt.grid(True, linestyle='--', alpha=0.6)
        plt.savefig('out/barrier_raw_runs.png') # saving the plot
        plt.show()


    # ==========================================
    # for each run translate the GPS coordinates into a relative vector of 1+4D: [SessionId, Distance, Delta Heading, Speed, Accel] 
    #   where Distance & Delta Heading are computed against the last sample of run (the barrier position)
    relative_runs = pipeline_step0_raw_to_relative_telemetry_independent(raw_runs)
    if False:
        print(f"Processed {len(relative_runs)} runs into relative telemetry vectors.")
        for i, run in enumerate(relative_runs[:3]):  # Just show the first 3
            for j, sample in enumerate(run):
                print(f"Run {i}: sample {j} SessionId: {sample[0]}, Distance: {sample[1]:.8f}, Delta Heading: {sample[2]:.4f}, Speed: {sample[3]:.4f}, Accel: {sample[4]:.4f}")
    # dump to csv for later use
    if True:
        dump_csv = pd.DataFrame(np.vstack(relative_runs), columns=['SessionId', 'Distance', 'Delta_Heading', 'Speed', 'Accel'])
        dump_csv.to_csv('out/barriers_relative_runs.csv', index=False)


    # ==========================================
    # normalize the 1+4D vectors of all runs to be between 0 and 1
    normalized_runs = normalize_runs(relative_runs)
    # replace the SessionId of the normalized_runs with one from relative_runs
    for i, run in enumerate(normalized_runs):
        for j, sample in enumerate(run):
            sample[0] = relative_runs[i][j][0]
    if False:
        print(f"Normalized {len(normalized_runs)} runs into 1+4D vectors.")
        for i, run in enumerate(normalized_runs[:3]):  # Just show the first
            for j, sample in enumerate(run):
                print(f"Run {i}: sample {j} SessionId: {sample[0]}, Distance: {sample[1]:.8f}, Delta Heading: {sample[2]:.4f}, Speed: {sample[3]:.4f}, Accel: {sample[4]:.4f}")
    # dump to csv for later use
    if True:
        dump_csv = pd.DataFrame(np.vstack(normalized_runs), columns=['SessionId', 'Distance', 'Delta_Heading', 'Speed', 'Accel'])
        dump_csv.to_csv('out/barriers_normalized_runs.csv', index=False)

    # normalized runs without SessionID for DTW distance matrix computation
    normalized_runs_no_session_id = [run[:, 1:] for run in normalized_runs]
    # dump to csv for later use
    if True:
        dump_csv = pd.DataFrame(np.vstack(normalized_runs_no_session_id), columns=['Distance', 'Delta_Heading', 'Speed', 'Accel'])
        dump_csv.to_csv('out/barriers_normalized_runs_no_session_id.csv', index=False)


    # ==========================================
    # compute the DTW distance matrix of all time series
    dtw_distance_matrix = build_distance_matrix(normalized_runs_no_session_id)


    # ==========================================
    # cluster the time series using DBSCAN with a distance threshold of TC1 (5.2) and a minimum of TS1 (3) samples per cluster
    TC1 = 5.2
    TS1 = 3
    dbscan = DBSCAN(eps=TC1, min_samples=TS1, metric="precomputed")
    labels = dbscan.fit_predict(dtw_distance_matrix)
    #
    # show clustering results
    if True:
        # replace -1 with '#' for better readability
        readable_labels = np.array(['~' if label == -1 else label for label in labels])

        print("\n--- Clustering Results ---")
        print(f"Total Runs Processed: {len(readable_labels)}")
        print(f"Cluster Assignments: {readable_labels}")
    # plot raw_runs that belong to each cluster
    if do_plots:
        unique_labels = set(labels)
        for cluster_id in unique_labels:
            if cluster_id == -1:
                continue  # Skip noise
            cluster_indices = np.where(labels == cluster_id)[0]
            for idx in cluster_indices:
                run = raw_runs[idx]
                run = sorted(run, key=lambda x: x[1])  # Sort by Time
                latitudes = [sample[2] for sample in run]
                longitudes = [sample[3] for sample in run]
                plt.plot(longitudes, latitudes, marker='o', label=f'Run {idx}')
            plt.title(f'Trajectories for cluster {cluster_id}')
            plt.xlabel('Longitude')
            plt.ylabel('Latitude')
            plt.legend()
            plt.grid(True, linestyle='--', alpha=0.6)
            plt.savefig(f'out/barrier_raw_runs_cluster_{cluster_id}.png') # saving the plot
            plt.show()


    # ==========================================
    # for each cluster/template/profile compute the medoid M2 of the time series and the medoid M1 of the barrier position, 
    #       for M1 will store the Lat/Lng/Alt of the last sample of the time series that has SessionId equal to the medoid M2 of the time series
    medoids = find_medoids(dtw_distance_matrix, labels)
    # 
    # print the medoids for each cluster
    if True:
        # 
        for cluster_id, medoid_idx in medoids.items():
            print(f"\n✅ Profile {cluster_id} Golden Template (medoid) found at index: {medoid_idx}")
    # plot the raw_runs of the medoids for each cluster
    if do_plots:
        #
        for cluster_id, medoid_idx in medoids.items():
            medoid_run = raw_runs[medoid_idx]
            medoid_run = sorted(medoid_run, key=lambda x: x[1])  # Sort by Time
            latitudes = [sample[2] for sample in medoid_run]
            longitudes = [sample[3] for sample in medoid_run]
            plt.plot(longitudes, latitudes, marker='o', label=f'Cluster {cluster_id} Medoid (Run {medoid_idx})')
        plt.title('Medoid Trajectories for Each Cluster')
        plt.xlabel('Longitude')
        plt.ylabel('Latitude')
        plt.legend()
        plt.grid(True, linestyle='--', alpha=0.6)
        plt.savefig('out/barrier_medoids.png') # saving the plot
        plt.show()
    

    # ==========================================
    # store the medoids in a database for later use in online processing
    M2_raw_runs = [raw_runs[medoid_idx] for cluster_id, medoid_idx in medoids.items()]
    M2_normalized_runs = [normalized_runs[medoid_idx] for cluster_id, medoid_idx in medoids.items()]
    M1_barrier_positions = [raw_runs[medoid_idx][-1] for cluster_id, medoid_idx in medoids.items()]
    # print(M2_raw_runs)
    # print(M2_normalized_runs)
    # print(M1_barrier_positions)

    pass


if __name__ == "__main__":
    
    pipeline_step0()


# method: 
# step 0 (offline/weekly): compute a fresh clustering of golden/manual time series using DTW,
#           a time seria is formed by 30 samples per sequence/session at 1Hz, specific to a triggering lift barrier event. 
#           for each cluster, a medoid of the path and a medoid of the barrier position will be computed and stored in a room database.
# step 1 (every second): get all barrier medoids from the database and compute distance to current position (max of 20 meters).
# step 2 (every second): if close enough then compute DTW distance of paths and if similar enough then trigger a lift barrier event.
#
# observation: it helps to keep the phone/device in a fixed position (like in a car mount) when manually collecting golden data
#               to avoid false positives due to erratic phone movement, especially when the lift is triggered manually.
#               the final objective is not to pick up the phone.



# detailed steps for step 0 for each barrier data:
#       - load data samples from file. 
#           samples are collected at 1Hz, forming a time series of 30 samples for each session as a 1+7D vectors: [SessionId, Time, Accuracy, Lat, Lng, Alt, Speed, Accel]
#       - data cleaning and filtering: 
#           - drop data samples that have a GPS accuracy worse than T1 (20 meters)
#           - drop time series that don't have T1a (30) samples (1Hz for 30 seconds)
#           - drop time series that have a GPS accuracy worse than T1b (10 meters) for the last sample (the last sample is used to compute the barrier position)
#       - convert data frame to list of runs (time series) grouped by SessionId, each run is a list of 1+6D vectors: [SessionId, Time, Lat, Lng, Alt, Speed, Accel]
#       - for each time series translate the GPS coordinates into a relative vector of 1+4D: [SessionId, Distance, Delta Heading, Speed, Accel] 
#               where Distance & Delta Heading are computed against the last sample of the run (the barrier position)
#       - normalize the 1+4D vectors of all time series to be between 0 and 1
#       - compute the DTW distance matrix of all time series
#       - cluster the time series using DBSCAN with a distance threshold of TC1 (5.2) and a minimum of TS1 (3) samples per cluster
#       - for each cluster/template/profile compute the medoid M2 of the time series and the medoid M1 of the barrier position, 
#               for M1 will store the Lat/Lng/Alt of the last sample of the time series that has SessionId equal to the medoid M2 of the time series
#       - store the medoids in a database for later use in online processing


# detailed steps for step 1 & 2 for each barrier data:
#       - get the current GPS position (Lat/Lng/Alt)
#       - get all barrier medoids M1 from the database, only the position of the barrier is needed for this step
#       - for each barrier medoid M1 compute the haversine distance to the current GPS position
#       - if the distance is less than T1 (20 meters) then compute the DTW distance
#           of the current path with the medoid M2 of the time series that has the same sessionID as the medoid M1 of the barrier position
#       - if the DTW distance is less than TC2 (5.0) then :
#               a) trigger a lift barrier event
#               b) and log the event with the current GPS coordinates (Accuracy,Lat,Lng,Alt,Speed,Accel) 
#                       and the M2 sessionId and the barrier medoid M1 position and the DTW distance to the barrier medoid M2
#               c) trigger a notification to the user that the lift barrier has been triggered with some of this information
#       - skip other barrier data (this or others) if this barrier has been triggered in the last 30 seconds (to avoid multiple triggers for the same barrier)