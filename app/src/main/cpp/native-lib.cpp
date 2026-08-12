#include <jni.h>
#include <string>
#include <android/log.h>
#include <vector>
#include <map>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <limits>

#include <sstream>
#include <format>

#define LOG_TAG "NativeLib"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_ro_andi_phonebarriers_NativeLib_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}

// ========================================================

struct MotionPoint {
    int id{};
    long long sessionId{};
    long long timestamp{};
    double accuracy{};
    double lat{};
    double lng{};
    double alt{};
    double speed{};
    double acceleration{};
    int barrierId{};
};

std::vector<MotionPoint> convertJavaArrayToNativePoints(JNIEnv* env, jobjectArray points) {

    jsize len = env->GetArrayLength(points);

    std::vector<MotionPoint> nativePoints;
    nativePoints.reserve(len);

    jobject firstPoint = env->GetObjectArrayElement(points, 0);
    jclass cls = env->GetObjectClass(firstPoint);

    jfieldID idField = env->GetFieldID(cls, "id", "I");
    jfieldID sessionIdField = env->GetFieldID(cls, "sessionId", "Ljava/lang/Long;");
    jfieldID timestampField = env->GetFieldID(cls, "timestamp", "J");
    jfieldID accuracyField = env->GetFieldID(cls, "accuracy", "F");
    jfieldID latField = env->GetFieldID(cls, "lat", "D");
    jfieldID lngField = env->GetFieldID(cls, "lng", "D");
    jfieldID altField = env->GetFieldID(cls, "alt", "D");
    jfieldID speedField = env->GetFieldID(cls, "speed", "F");
    jfieldID accelerationField = env->GetFieldID(cls, "acceleration", "F");
    jfieldID barrierIdField = env->GetFieldID(cls, "barrierId", "Ljava/lang/Integer;");

    jclass longCls = env->FindClass("java/lang/Long");
    jmethodID longValueMethod = env->GetMethodID(longCls, "longValue", "()J");

    jclass intCls = env->FindClass("java/lang/Integer");
    jmethodID intValueMethod = env->GetMethodID(intCls, "intValue", "()I");

    for (int i = 0; i < len; i++) {
        jobject pObj = env->GetObjectArrayElement(points, i);

        MotionPoint mp {};
        mp.id = env->GetIntField(pObj, idField);

        jobject sIdObj = env->GetObjectField(pObj, sessionIdField);
        mp.sessionId = (sIdObj != nullptr) ? env->CallLongMethod(sIdObj, longValueMethod) : -1;
        if (sIdObj) env->DeleteLocalRef(sIdObj);

        mp.timestamp = env->GetLongField(pObj, timestampField);
        mp.accuracy = env->GetFloatField(pObj, accuracyField);
        mp.lat = env->GetDoubleField(pObj, latField);
        mp.lng = env->GetDoubleField(pObj, lngField);
        mp.alt = env->GetDoubleField(pObj, altField);
        mp.speed = env->GetFloatField(pObj, speedField);
        mp.acceleration = env->GetFloatField(pObj, accelerationField);

        jobject bIdObj = env->GetObjectField(pObj, barrierIdField);
        mp.barrierId = (bIdObj != nullptr) ? env->CallIntMethod(bIdObj, intValueMethod) : -1;
        if (bIdObj) env->DeleteLocalRef(bIdObj);

        nativePoints.push_back(mp);
        env->DeleteLocalRef(pObj);
    }

    env->DeleteLocalRef(firstPoint);
    env->DeleteLocalRef(cls);
    env->DeleteLocalRef(longCls);
    env->DeleteLocalRef(intCls);

    return nativePoints;
}

typedef std::map<long long, std::vector<std::vector<MotionPoint>::const_iterator>> mapSid2RawPointRefType;

/**
 *  cleaning and filtering (on session map):
 *      - drop points with bad accuracy, worse than T1 (20 meters)
 *      - drop sessions that have an accuracy worse than T1b (10 meters) for the last point/sample
 *          (the last sample is used as anchor for the barrier)
 *      - drop sessions with less than T1a (30) points/samples (1Hz for 30 seconds)
 * @param sessionMap
 */
mapSid2RawPointRefType cleanAndFilterSessions(const mapSid2RawPointRefType & sessionMap) {

    double T1 = 20.0;
    int T1a = 30;
    double T1b = 10.0;

    mapSid2RawPointRefType cleanedSessionMap;

    for (auto& pairS2vPR : sessionMap) {

        // make a copy of the vector
        auto tempVector = pairS2vPR.second;

        LOGD("Processing session %lld with %zu motion points", pairS2vPR.first, tempVector.size());

        // remove points with bad accuracy, worse than T1
        tempVector.erase(
                std::remove_if(
                        tempVector.begin(),
                        tempVector.end(),
                        [&](auto& mpIt) { return mpIt->accuracy >= T1;}),
                    tempVector.end());

        LOGD("Removed points with bad accuracy, worse than T1. %zu points remaining", tempVector.size());

        // drop sessions that have an accuracy worse than T1b for the last point/sample
        if (!tempVector.empty()) {
            auto lastPoint = tempVector.back();
            if (lastPoint->accuracy >= T1b) {
                tempVector.clear();
            }
        }

        LOGD("Removed session if it has an accuracy worse than T1b, %zu points remaining", tempVector.size());

        // add in cleaned session map only sessions with exactly T1a points/samples
        if (tempVector.size() >= T1a) {
            cleanedSessionMap[pairS2vPR.first] = tempVector;
        }

        LOGD("Added in cleaned session map only sessions with more than T1a points/samples, %zu sessions", cleanedSessionMap.size());

    }

    return cleanedSessionMap;
}

// ========================================================

struct RelativePoint {
    long long sessionId{};
    long long timestamp{};
    double distance{};
    double deltaHeading{};
    double speed{};
    double acceleration{};

    std::string toStringJSONLike() const {
        return std::string("{")
        + "\"sessionId\":" + std::to_string(sessionId)
        + "," + "\"timestamp\":" + std::to_string(timestamp)

        + "," + "\"distance\":" + std::to_string(distance)
        + "," + "\"deltaHeading\":" + std::to_string(deltaHeading)
        + "," + "\"speed\":" + std::to_string(speed)
        + "," + "\"acceleration\":" + std::to_string(acceleration)
        + "}";
    }
};

typedef std::map<long long, std::vector<RelativePoint>> mapSid2RelPointType;

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

inline double degToRad(double deg) { return deg * M_PI / 180.0; }
inline double radToDeg(double rad) { return rad * 180.0 / M_PI; }

double calculateHaversineDistanceLLA(double lat1, double lon1, double alt1,
                                     double lat2, double lon2, double alt2) {
    const double R = 6371000.0;
    double phi1 = degToRad(lat1);
    double phi2 = degToRad(lat2);
    double deltaPhi = degToRad(lat2 - lat1);
    double deltaLambda = degToRad(lon2 - lon1);

    double a = std::sin(deltaPhi / 2.0) * std::sin(deltaPhi / 2.0) +
               std::cos(phi1) * std::cos(phi2) *
               std::sin(deltaLambda / 2.0) * std::sin(deltaLambda / 2.0);
    double c = 2.0 * std::atan2(std::sqrt(a), std::sqrt(1.0 - a));

    double horizontalDistance = R * c;
    double verticalDistance = alt2 - alt1;

    return std::sqrt(horizontalDistance * horizontalDistance + verticalDistance * verticalDistance);
}

double calculateBearing(double lat1, double lon1, double lat2, double lon2) {
    double phi1 = degToRad(lat1);
    double phi2 = degToRad(lat2);
    double deltaLambda = degToRad(lon2 - lon1);

    double y = std::sin(deltaLambda) * std::cos(phi2);
    double x = std::cos(phi1) * std::sin(phi2) -
               std::sin(phi1) * std::cos(phi2) * std::cos(deltaLambda);

    double theta = std::atan2(y, x);
    double bearing = radToDeg(theta);
    return std::fmod(bearing + 360.0, 360.0);
}

double getDeltaHeading(double currentLat, double currentLon, double currentGpsHeading,
                       double barrierLat, double barrierLon) {
    double idealBearing = calculateBearing(currentLat, currentLon, barrierLat, barrierLon);
    double deltaHeading = std::abs(currentGpsHeading - idealBearing);
    if (deltaHeading > 180.0) {
        deltaHeading = 360.0 - deltaHeading;
    }
    return deltaHeading;
}

/**
 * convert sessions from 1+6D [SessionId, Time, Lat, Lng, Alt, Speed, Accel] points
 *      to 1+1+4D [SessionId, Time, Distance, Delta Heading, Speed, Accel] points
 *      where Distance & Delta Heading are computed against the last point/sample of session (the barrier position)
 * @param sessionMap
 * @return
 */
mapSid2RelPointType convertSessionsToRelativePoints(const mapSid2RawPointRefType & sessionMap) {
    mapSid2RelPointType relativeSessionMap;

    for (const auto& pair : sessionMap) {
        long long sid = pair.first;
        const auto& rawPoints = pair.second;
        if (rawPoints.empty()) continue;

        std::vector<RelativePoint> relPoints;
        relPoints.reserve(rawPoints.size());

        // Target is the last point
        const auto& target = *rawPoints.back();

        for (size_t i = 0; i < rawPoints.size(); ++i) {
            const auto& current = *rawPoints[i];

            RelativePoint rp;
            rp.sessionId = sid;
            rp.timestamp = current.timestamp;
            rp.speed = current.speed;
            rp.acceleration = current.acceleration;

            // 1. Distance
            rp.distance = (double)calculateHaversineDistanceLLA(
                    current.lat, current.lng, current.alt,
                    target.lat, target.lng, target.alt
            );

            // 2. Inferred heading
            double currentHeading = 0.0;
            if (i > 0) {
                const auto& prev = *rawPoints[i-1];
                currentHeading = calculateBearing(prev.lat, prev.lng, current.lat, current.lng);
            } else if (rawPoints.size() > 1) {
                const auto& next = *rawPoints[i+1];
                currentHeading = calculateBearing(current.lat, current.lng, next.lat, next.lng);
            }

            // 3. Delta heading
            rp.deltaHeading = (double)getDeltaHeading(
                    current.lat, current.lng, currentHeading,
                    target.lat, target.lng
            );

            relPoints.push_back(rp);
        }

        // some logging-debugging
        if (sid==1774936118336){// 1785390077860 // 1784699320188
            // log relPoints
            LOGD("sid,timestamp,distance,deltaHeading,speed,acceleration [RELATIVE]");
            for (const auto& rp : relPoints) {
                LOGD("%lld,%lld,%f,%f,%f,%f",
                     rp.sessionId, rp.timestamp, rp.distance, rp.deltaHeading, rp.speed, rp.acceleration);
            }
        }

        relativeSessionMap[sid] = std::move(relPoints);
    }

    return relativeSessionMap;
}

// ========================================================

struct NormPoint {
    long long sessionId{};
    long long timestamp{};
    double distance{};
    double deltaHeading{};
    double speed{};
    double acceleration{};
};

typedef std::map<long long, std::vector<NormPoint>> mapSid2NormPointType;

/**
 * normalize the 1+1+4D vectors of all sessions/runs to be between 0 and 1
 * @param relativeSessionMap
 * @param pointOfMins
 * @param pointOfMaxs
 * @return
 */
mapSid2NormPointType normalizeSessions(const mapSid2RelPointType & relativeSessionMap,
                                       RelativePoint & pointOfMins,
                                       RelativePoint & pointOfMaxs) {

    mapSid2NormPointType normalizedSessionMap;

    if (relativeSessionMap.empty()) return normalizedSessionMap;

    // compute mins & maxs
    pointOfMins.distance = relativeSessionMap.begin()->second[0].distance;
    pointOfMins.deltaHeading = relativeSessionMap.begin()->second[0].deltaHeading;
    pointOfMins.speed = relativeSessionMap.begin()->second[0].speed;
    pointOfMins.acceleration = relativeSessionMap.begin()->second[0].acceleration;
    //
    pointOfMaxs.distance = pointOfMins.distance;
    pointOfMaxs.deltaHeading = pointOfMins.deltaHeading;
    pointOfMaxs.speed = pointOfMins.speed;
    pointOfMaxs.acceleration = pointOfMins.acceleration;
    //
    for (const auto& pair : relativeSessionMap) {

        // update MINs
        {
            {
                auto minPoint =
                        std::min_element(pair.second.begin(), pair.second.end(),
                                         [](const auto& v1, const auto& v2)
                                         {return v1.distance < v2.distance;});
                if (minPoint->distance < pointOfMins.distance) pointOfMins.distance = minPoint->distance;
            }

            {
                auto minPoint =
                        std::min_element(pair.second.begin(), pair.second.end(),
                                         [](const auto& v1, const auto& v2)
                                         {return v1.deltaHeading < v2.deltaHeading;});
                if (minPoint->deltaHeading < pointOfMins.deltaHeading) pointOfMins.deltaHeading = minPoint->deltaHeading;
            }

            {
                auto minPoint =
                        std::min_element(pair.second.begin(), pair.second.end(),
                                         [](const auto& v1, const auto& v2)
                                         {return v1.speed < v2.speed;});
                if (minPoint->speed < pointOfMins.speed) pointOfMins.speed = minPoint->speed;
            }

            {
                auto minPoint =
                        std::min_element(pair.second.begin(), pair.second.end(),
                                         [](const auto& v1, const auto& v2)
                                         {return v1.acceleration < v2.acceleration;});
                if (minPoint->acceleration < pointOfMins.acceleration) pointOfMins.acceleration = minPoint->acceleration;
            }
        }



        // update MAXs
        {
            {
                auto maxPoint =
                        std::min_element(pair.second.begin(), pair.second.end(),
                                         [](const auto& v1, const auto& v2)
                                         {return v1.distance > v2.distance;});
                if (maxPoint->distance > pointOfMaxs.distance) pointOfMaxs.distance = maxPoint->distance;
            }

            {
                auto maxPoint =
                        std::min_element(pair.second.begin(), pair.second.end(),
                                         [](const auto& v1, const auto& v2)
                                         {return v1.deltaHeading > v2.deltaHeading;});
                if (maxPoint->deltaHeading > pointOfMaxs.deltaHeading) pointOfMaxs.deltaHeading = maxPoint->deltaHeading;
            }

            {
                auto maxPoint =
                        std::min_element(pair.second.begin(), pair.second.end(),
                                         [](const auto& v1, const auto& v2)
                                         {return v1.speed > v2.speed;});
                if (maxPoint->speed > pointOfMaxs.speed) pointOfMaxs.speed = maxPoint->speed;
            }

            {
                auto maxPoint =
                        std::min_element(pair.second.begin(), pair.second.end(),
                                         [](const auto& v1, const auto& v2)
                                         {return v1.acceleration > v2.acceleration;});
                if (maxPoint->acceleration > pointOfMaxs.acceleration) pointOfMaxs.acceleration = maxPoint->acceleration;
            }
        }


    }

    // compute normalized
    for (const auto& pair : relativeSessionMap) {

        long long sid = pair.first;
        const auto &relPoints = pair.second;

        std::vector<NormPoint> normPoints;
        normPoints.reserve(relPoints.size());
        for (const auto& rp : relPoints) {
            NormPoint np;
            np.sessionId = sid;
            np.timestamp = rp.timestamp;

            np.distance = (rp.distance - pointOfMins.distance) / (pointOfMaxs.distance - pointOfMins.distance);
            np.deltaHeading = (rp.deltaHeading - pointOfMins.deltaHeading) / (pointOfMaxs.deltaHeading - pointOfMins.deltaHeading);
            np.speed = (rp.speed - pointOfMins.speed) / (pointOfMaxs.speed - pointOfMins.speed);
            np.acceleration = (rp.acceleration - pointOfMins.acceleration) / (pointOfMaxs.acceleration - pointOfMins.acceleration);

            normPoints.push_back(np);
        }

        // some logging-debugging
        if (sid==1774936118336){// 1785390077860 // 1784699320188
            // log normPoints
            LOGD("sid,timestamp,distance,deltaHeading,speed,acceleration [NORMALIZED]");
            for (const auto& np : normPoints) {
                LOGD("%lld,%lld,%f,%f,%f,%f",
                     np.sessionId, np.timestamp, np.distance, np.deltaHeading, np.speed, np.acceleration);
            }
        }

        normalizedSessionMap[sid] = std::move(normPoints);
    }

    return normalizedSessionMap;
}

// ========================================================

/**
 * calculates the distance between two 4D feature points (Distance, Delta Heading, Speed, Acceleration)
 * @param p1
 * @param p2
 * @return
 */
double euclideanDistance(const NormPoint& p1, const NormPoint& p2) {
    double d1 = p1.distance - p2.distance;
    double d2 = p1.deltaHeading - p2.deltaHeading;
    double d3 = p1.speed - p2.speed;
    double d4 = p1.acceleration - p2.acceleration;
    return std::sqrt(d1 * d1 + d2 * d2 + d3 * d3 + d4 * d4);
}

/**
 * computes the DTW distance between two sessions
 * @param s1
 * @param s2
 * @return
 */
double computeDtwDistance(const std::vector<NormPoint>& s1, const std::vector<NormPoint>& s2) {
    size_t n = s1.size();
    size_t m = s2.size();
    if (n == 0 || m == 0) return 0.0;

    std::vector<std::vector<double>> dtw(n + 1, std::vector<double>(m + 1, std::numeric_limits<double>::infinity()));
    dtw[0][0] = 0.0;

    for (size_t i = 1; i <= n; ++i) {
        for (size_t j = 1; j <= m; ++j) {
            double cost = euclideanDistance(s1[i - 1], s2[j - 1]);
            dtw[i][j] = cost + std::min({dtw[i - 1][j], dtw[i][j - 1], dtw[i - 1][j - 1]});
        }
    }
    return dtw[n][m];
}

/**
 * calculates the N x N pairwise DTW distance matrix between sessions
 * @param normalizedSessionMap
 * @param sids (output) ordered list of session IDs
 * @return
 */
std::vector<std::vector<double>> buildDistanceMatrix(const mapSid2NormPointType& normalizedSessionMap,
                                                    std::vector<long long>& sids) {
    size_t N = normalizedSessionMap.size();
    std::vector<std::vector<double>> matrix(N, std::vector<double>(N, 0.0));

    std::vector<const std::vector<NormPoint>*> sessions;
    sessions.reserve(N);
    sids.reserve(N);

    for (const auto& pair : normalizedSessionMap) {
        sids.push_back(pair.first);
        sessions.push_back(&pair.second);
    }

    LOGD("Building %zu x %zu DTW Distance Matrix...", N, N);
    for (size_t i = 0; i < N; ++i) {
        for (size_t j = i + 1; j < N; ++j) {
            double dist = computeDtwDistance(*sessions[i], *sessions[j]);
            matrix[i][j] = dist;
            matrix[j][i] = dist;
        }
    }
    return matrix;
}

/**
 * clusters sessions using DBSCAN
 * @param distanceMatrix
 * @param eps distance threshold
 * @param minSamples minimum points to form a cluster
 * @return list of labels for each index (-1 for noise, 0+ for cluster ID)
 */
std::vector<int> runDbscan(const std::vector<std::vector<double>>& distanceMatrix, double eps, int minSamples) {
    size_t n = distanceMatrix.size();
    if (n == 0) return {};

    std::vector<int> labels(n, -2); // -2: unvisited, -1: noise, 0+: cluster ID
    int clusterId = 0;

    for (size_t i = 0; i < n; ++i) {
        if (labels[i] != -2) continue;

        // Find neighbors
        std::vector<size_t> neighbors;
        for (size_t j = 0; j < n; ++j) {
            if (distanceMatrix[i][j] <= eps) {
                if ( i != j ) neighbors.push_back(j);
            }
        }

        if (neighbors.size() < (size_t)minSamples) {
            labels[i] = -1; // Noise
        } else {
            // Expand cluster
            labels[i] = clusterId;

            // neighbors will be our seed set. We'll use an index to iterate because seedSet will grow.
            std::vector<size_t> seedSet = neighbors;
            for (size_t k = 0; k < seedSet.size(); ++k) {
                size_t currentIdx = seedSet[k];

                if (labels[currentIdx] == -1) {
                    labels[currentIdx] = clusterId;
                }
                if (labels[currentIdx] != -2) continue;

                labels[currentIdx] = clusterId;

                // Find neighbors of currentIdx
                std::vector<size_t> currentNeighbors;
                for (size_t j = 0; j < n; ++j) {
                    if (distanceMatrix[currentIdx][j] <= eps) {
                        if ( currentIdx != j) currentNeighbors.push_back(j);
                    }
                }

                if (currentNeighbors.size() >= (size_t)minSamples) {
                    for (size_t neighborIdx : currentNeighbors) {
                        // Only add to seedSet if it's not already there
                        if (std::find(seedSet.begin(), seedSet.end(), neighborIdx) == seedSet.end()) {
                            seedSet.push_back(neighborIdx);
                        }
                    }
                }
            }
            clusterId++;
        }
    }
    
    // from 0+: cluster ID to 1+: cluster ID
    for (int & label : labels) label++;
    
    return labels;
}

/**
 * identifies the Medoid (most central run index) for each discovered cluster
 * @param distanceMatrix
 * @param clusterLabels
 * @return map clusterId -> medoid index in distanceMatrix
 */
std::map<int, int> findMedoids(const std::vector<std::vector<double>>& distanceMatrix, const std::vector<int>& clusterLabels) {
    std::map<int, std::vector<int>> clusterToIndices;
    for (size_t i = 0; i < clusterLabels.size(); ++i) {
        if (clusterLabels[i] > 0) {
            clusterToIndices[clusterLabels[i]].push_back((int)i);
        }
    }

    std::map<int, int> medoids;
    for (auto const& pair : clusterToIndices) {
        int clusterId = pair.first;
        const std::vector<int>& indices = pair.second;

        double minSum = std::numeric_limits<double>::max();
        int medoidIdx = -1;

        for (int i : indices) {
            double currentSum = 0.0;
            for (int j : indices) {
                currentSum += distanceMatrix[i][j];
            }

            if (currentSum < minSum) {
                minSum = currentSum;
                medoidIdx = i;
            }
        }
        medoids[clusterId] = medoidIdx;
    }
    return medoids;
}

// ========================================================

extern "C" JNIEXPORT jstring JNICALL
Java_ro_andi_phonebarriers_NativeLib_dtwClassifyAndFindMedoidsForPathsAndAnchors(
        JNIEnv* env,
        jobject /* this */,
        jobjectArray points) {

    auto start = std::chrono::high_resolution_clock::now();

    jsize len = env->GetArrayLength(points);
    LOGD("Processing %d motion points in C++", len);

    if (len == 0) return env->NewStringUTF("{}");

    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    // convert to native points
    std::vector<MotionPoint> nativePoints = convertJavaArrayToNativePoints(env, points);

    // build map of sessionId to vector of points ordered by timestamp
    mapSid2RawPointRefType sessionMap;
    {
        for ( auto mpIt = nativePoints.begin(); mpIt != nativePoints.end(); mpIt++)  {
            sessionMap[ mpIt->sessionId ].emplace_back( mpIt );
        }

        for (auto& pair : sessionMap) {
            std::sort(pair.second.begin(), pair.second.end(),
                      [](const auto a, const auto b)
                      { return a->timestamp < b->timestamp; }
                      );
        }
    }

    LOGD("Converted to native points. Found %zu unique sessions.", sessionMap.size());


    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    // cleaning and filtering (on session map)
    auto cleanedSessionMap = cleanAndFilterSessions(sessionMap);

    LOGD("Cleaned and filtered native points. Observed %zu clean sessions.", cleanedSessionMap.size());


    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    // convert sessions from 1+6D [SessionId, Time, Lat, Lng, Alt, Speed, Accel] points
    //      to 1+1+4D [SessionId, Time, Distance, Delta Heading, Speed, Accel] points
    //      where Distance & Delta Heading are computed against the last point/sample of session (the barrier position)
    auto relativeSessionMap = convertSessionsToRelativePoints(cleanedSessionMap);

    LOGD("Converted to relative telemetry. Processing %zu relative sessions.", relativeSessionMap.size());


    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    // normalize the 1+1+4D vectors of all sessions/runs to be between 0 and 1
    auto relativePointOfMins = RelativePoint();
    auto relativePointOfMaxs = RelativePoint();
    auto normalizedSessionMap = normalizeSessions(relativeSessionMap, relativePointOfMins, relativePointOfMaxs);

    LOGD("Normalized relative telemetry. Processed %zu normalized sessions.", normalizedSessionMap.size());
    LOGD("Point of mins: %s", relativePointOfMins.toStringJSONLike().c_str());
    LOGD("Point of maxs: %s", relativePointOfMaxs.toStringJSONLike().c_str());


    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    // compute the DTW distance matrix of all sessions / runs / time series
    std::vector<long long> sids;
    auto dtwDistanceMatrix = buildDistanceMatrix(normalizedSessionMap, sids);

    LOGD("Completed DTW Distance Matrix computation.");
    LOGD("list of sessions ids in order: ");
    std::string strSIDs = "[";
    for (size_t i = 0; i < sids.size(); ++i) {
        strSIDs += std::to_string(sids[i]);
        if (i < sids.size() - 1) strSIDs += ',';
        else strSIDs += ']';
    }
    LOGD("%s", strSIDs.c_str());
    LOGD("------------------------------------------");
    LOGD("DTW Distance Matrix: %zu x %zu", dtwDistanceMatrix.size(), dtwDistanceMatrix[0].size());
    for (size_t i = 0; i < dtwDistanceMatrix.size(); ++i) {
        std::string mline{"[ "};
        for (size_t j = 0; j < dtwDistanceMatrix[0].size(); ++j) {
            mline += std::to_string(dtwDistanceMatrix[i][j]) + " , ";
        }
        mline += ']';
        LOGD("%s",mline.c_str());
    }


    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    // cluster the sessions / runs / time series using DBSCAN with a distance threshold of TC1 (5.2) and a minimum of TS1 (3) samples per cluster
    double TC1 = 5.2;
    int TS1 = 3;
    auto clusterLabels = runDbscan(dtwDistanceMatrix, TC1, TS1);

    LOGD("Completed DBSCAN clustering.");

    // assign each session id with a cluster label id ( 1-based )
    std::map<long long, int> sidToCluster;
    int maxClusterId = 0;
    for (size_t i = 0; i < sids.size(); ++i) {
        sidToCluster[sids[i]] = clusterLabels[i];
        if (clusterLabels[i] > maxClusterId) maxClusterId = clusterLabels[i];
    }
    int clusterCount = maxClusterId;

    // build clusterAssignments & cluster2sessionIdx strings
    std::string clusterAssignments = "[";
    for (size_t i = 0; i < sids.size(); ++i) {
        clusterAssignments += std::to_string(clusterLabels[i]);
        if (i < sids.size() - 1) clusterAssignments += ',';
    }
    clusterAssignments += ']';
    //
    std::string cluster2sessionIdx = "[";
    for (size_t i = 1; i < clusterCount+1; ++i) {
        cluster2sessionIdx += "{" + std::to_string(i) + ":";
        cluster2sessionIdx += '[';
        for (size_t j = 0; j < sids.size(); ++j) {
            if (clusterLabels[j] == i) {
                cluster2sessionIdx += std::to_string(j);
                if (j < sids.size() - 1) cluster2sessionIdx += ',';
            }
        }
        cluster2sessionIdx += "]}";
        if (i < clusterCount) cluster2sessionIdx += ',';
    }
    cluster2sessionIdx += ']';


    LOGD("Found %d clusters.", clusterCount);
    LOGD("Assignments, session to cluster: %s", clusterAssignments.c_str());
    LOGD("Assignments, cluster to sessions idx: %s", cluster2sessionIdx.c_str());


    //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    // for each cluster/template/profile compute the medoid M2 of the session / run / time series
    //      and the medoid M1 of the barrier anchor position,
    //   for M1 will store the Lat/Lng/Alt of the last sample of the session / run / time series
    //      that has SessionId equal to the medoid M2 of the time series
    auto medoids = findMedoids(dtwDistanceMatrix, clusterLabels);

    std::string medoidsIdxJson = "{";
    std::string medoidsSidJson = "{";
    std::string medoidsRelativePointsJSON = "[";
    size_t mCount = 0;
    for (auto const& pair : medoids) {
        int clusterId = pair.first;
        int medoidIdx = pair.second;
        long long medoidSid = sids[medoidIdx];
        medoidsIdxJson += "\"" + std::to_string(clusterId) + "\":" + std::to_string(medoidIdx);
        medoidsSidJson += "\"" + std::to_string(clusterId) + "\":" + std::to_string(medoidSid);
        //
        for (auto const& rp : relativeSessionMap[medoidSid]) {
            medoidsRelativePointsJSON += rp.toStringJSONLike() + ",";
        }
        if (++mCount < medoids.size()) {
            medoidsIdxJson += ',';
            medoidsSidJson += ',';
        }
        else {
            medoidsRelativePointsJSON.resize(medoidsRelativePointsJSON.size() - 1);
        }
        LOGD("Cluster %d medoid: idx=%d", clusterId, medoidIdx);
        LOGD("Cluster %d medoid: sessionId=%lld", clusterId, medoidSid);
    }
    medoidsIdxJson += '}';
    medoidsSidJson += '}';
    medoidsRelativePointsJSON += ']';

    

    auto end = std::chrono::high_resolution_clock::now();
    auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();

    // return session count as string for now
    std::string result = "{ \"processing_time_ms\":" + std::to_string(duration) +
            ", \"sessions_count\":" + std::to_string(sessionMap.size()) +
            ", \"cleanedSessionsCount\":" + std::to_string(cleanedSessionMap.size()) +
                ", \"clusterCount\":" + std::to_string(clusterCount) +
                ", \"cluster2sessionIdx\":" + cluster2sessionIdx +
                    ", \"medoidsIdx\":" + medoidsIdxJson +
                    ", \"medoidsSid\":" + medoidsSidJson +
                    ", \"medoidsRelativePoints\":" + medoidsRelativePointsJSON +
                ", \"relativePointOfMins\": " + relativePointOfMins.toStringJSONLike() +
                ", \"relativePointOfMaxs\": " + relativePointOfMaxs.toStringJSONLike() +
                "}";
    return env->NewStringUTF(result.c_str());
}
