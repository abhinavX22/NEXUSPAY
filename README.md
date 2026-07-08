# NexusPay - Offline UPI Mesh Demo

This project provides a Spring Boot backend demonstrating **offline UPI payments transmitted via a Bluetooth-style mesh network**. Imagine you are in an area with zero cellular connectivity, like a basement. You initiate a ₹500 transfer to a friend. Your device encrypts the transaction, broadcasts it to neighboring devices, and the encrypted packet is relayed from phone to phone. Eventually, when *any* device in this chain reaches an area with internet access (e.g., 4G), it automatically uploads the packet to this backend. The backend then decrypts the data, ensures it isn't a duplicate, and settles the payment.

This repository contains the **server-side implementation** of this architecture, alongside a software-based mesh network simulator. This allows you to experience the complete end-to-end flow on a single machine without requiring actual Bluetooth hardware.

---

## Table of Contents

1. [Key Capabilities Demonstrated](#key-capabilities-demonstrated)
2. [Getting Started](#getting-started)
3. [Step-by-Step Demo Flow](#step-by-step-demo-flow)
4. [System Architecture](#system-architecture)
5. [Core Challenges and Solutions](#core-challenges-and-solutions)
6. [Project Structure](#project-structure)
7. [API Documentation](#api-documentation)
8. [Running Tests](#running-tests)
9. [Production Considerations](#production-considerations)
10. [Inherent Limitations](#inherent-limitations)

---

## Key Capabilities Demonstrated

This application validates three crucial aspects of the end-to-end system:

1. **Secure transmission through untrusted nodes**: A transaction can travel from the sender to the server via intermediate devices without anyone being able to intercept or alter it (using Hybrid RSA + AES-GCM encryption).
2. **Strict exactly-once processing**: Even if multiple bridge devices simultaneously deliver the exact same payment packet to the backend, it will only be settled once. (Achieved through idempotency and atomic compare-and-set operations on the ciphertext hash).
3. **Robust payload validation**: Any replayed or tampered packets are immediately discarded before reaching the ledger.

You can observe all three of these mechanisms functioning in real-time through the dashboard.

---

## Getting Started

### Prerequisites

- **JDK 17 or higher** must be installed and available in your PATH (or via `JAVA_HOME`). You can verify this by running `java -version`.
- No additional infrastructure (like databases, Redis, or local Maven installations) is required. The Maven wrapper and in-memory components handle everything.

### Running on Windows

Open your terminal in the project directory and execute:

```cmd
mvnw.cmd spring-boot:run
```

During the initial run, the wrapper will download Maven (~10 MB) and all project dependencies (~80 MB), which may take a few minutes. Future startups will complete in seconds.

### Running on macOS/Linux

```bash
./mvnw spring-boot:run
```

### Accessing the Dashboard

Once the console outputs a message similar to `Started NexusPayApplication in X.XXX seconds`, navigate your browser to:

**http://localhost:8080**

You will be greeted by a dark-themed dashboard equipped with all the controls necessary to run the simulation.

### Stopping the Application

Simply press `Ctrl+C` in your terminal.

### Executing Tests

```cmd
mvnw.cmd test
```

The most critical test to review is `IdempotencyConcurrencyTest`. It simulates three independent threads attempting to deliver the identical packet simultaneously, strictly verifying that only one settlement occurs.

---

## Step-by-Step Demo Flow

The dashboard provides four primary actions to simulate the entire lifecycle. We recommend following this sequence:

### Step 1 — Initialize a Transaction

Select a sender, receiver, transaction amount, and PIN. Then, click **"📤 Inject into Mesh"**.

**Behind the scenes:**
- The backend acts as the sender's mobile device.
- It constructs a `PaymentInstruction` containing a unique nonce and the current timestamp.
- It encrypts this payload using the server's RSA public key (utilizing the hybrid encryption model detailed below).
- The resulting ciphertext is packaged into a `MeshPacket` with a Time-To-Live (TTL) of 5.
- This packet is handed over to `phone-alice`, an offline virtual device in the simulation.

On the dashboard, you will notice that `phone-alice` now contains 1 packet.

### Step 2 — Execute Gossip Protocol

Click the **"🔄 Run Gossip Round"** button. Then, click it again.

During each round, any device holding a packet broadcasts it to every other device within its simulated "Bluetooth range" (which, for this demo, includes all devices). The TTL is decremented with each hop.

After the first round, every device will have a copy of the packet. After the second round, every device still retains it, but with a reduced TTL. In a real-world scenario, this propagation happens organically as individuals pass by one another.

### Step 3 — Bridge Devices Connect to the Internet

Click **"📡 Bridges Upload to Backend"**.

In our simulated environment, `phone-bridge` is the only device configured with `hasInternet=true`. This action mimics that specific phone gaining 4G access and POSTing all the packets it holds to the `/api/bridge/ingest` endpoint.

The server's ingestion pipeline then triggers:
1. Calculates the `SHA-256` hash of the ciphertext.
2. Attempts to claim this hash within the idempotency cache.
3. If successfully claimed, decrypts the payload using the server's RSA private key.
4. Validates freshness (ensuring the `signedAt` timestamp is within the last 24 hours).
5. Executes the debit and credit operations within a single database transaction.

Observe the **Account Balances** section to see the updated funds, and check the **Transaction Ledger** for the newly recorded entry.

### Step 4 — Showcasing Idempotency (The Core Feature)

Clear the mesh state. Inject a new packet, and run the gossip protocol twice. At this point, **all 5 simulated devices possess the same packet, acting as a complex multi-bridge scenario**.

To observe idempotency handling:
1. Inject a packet once.
2. Run Gossip twice.
3. Click "Flush Bridges". By default, only `phone-bridge` has internet access, so a single upload occurs.

To rigorously test the *concurrent duplicate* scenario, execute the following test:
```cmd
mvnw.cmd test -Dtest=IdempotencyConcurrencyTest#singlePacketDeliveredByThreeBridgesSettlesExactlyOnce
```

This automated test constructs a single packet and dispatches 3 concurrent threads to `BridgeIngestionService.ingest()`. It reliably proves that exactly one transaction succeeds, the remaining two are discarded as duplicates, and the sender's account is debited just once.

---

## System Architecture

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                         SENDER DEVICE (offline)                         │
│  PaymentInstruction { sender, receiver, amount, pinHash, nonce, time }  │
│              │                                                          │
│              ▼ Encrypt using server's RSA public key                    │
│   MeshPacket { packetId, ttl, createdAt, ciphertext }                   │
└──────────────────────────────────────┬──────────────────────────────────┘
                                       │ Bluetooth gossip transfer
                                       ▼
        ┌─────────┐  hop   ┌─────────┐  hop   ┌─────────┐
        │stranger1│ ─────▶ │stranger2│ ─────▶ │ bridge  │ ◀── gains 4G access
        └─────────┘        └─────────┘        └────┬────┘
                                                   │
                                                   ▼ HTTPS POST
┌─────────────────────────────────────────────────────────────────────────┐
│                    SPRING BOOT BACKEND (NexusPay Server)                │
│                                                                         │
│  /api/bridge/ingest                                                     │
│       │                                                                 │
│       ▼                                                                 │
│  [1] Compute ciphertext hash (SHA-256)                                  │
│       │                                                                 │
│       ▼                                                                 │
│  [2] IdempotencyService.claim(hash)  ◀── Atomic putIfAbsent (similar to │
│       │                                  Redis SETNX). Duplicates are   │
│       │                                  rejected early.                │
│       ▼                                                                 │
│  [3] HybridCryptoService.decrypt(ciphertext)                            │
│       │       (RSA-OAEP extracts AES key; AES-GCM decrypts payload      │
│       │        AND validates auth tag — tampering throws exception)     │
│       ▼                                                                 │
│  [4] Freshness validation: signedAt must be within last 24h             │
│       │                                                                 │
│       ▼                                                                 │
│  [5] SettlementService.settle()                                         │
│       @Transactional: perform debit, credit, and ledger insertion.      │
│       @Version on Account entity = optimistic locking for safety.       │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Core Challenges and Solutions

### Challenge 1: Ensuring Data Security Across Untrusted Nodes

Since a random individual's device might relay your transaction, how do you prevent them from viewing or modifying the payment details?

**Solution: Hybrid Encryption (RSA-OAEP + AES-GCM)**

The sender encrypts the transaction payload using the server's public key. Because only the backend possesses the corresponding private key, intermediary devices only see an opaque ciphertext block.

However, RSA is only suitable for small payloads (~245 bytes for a 2048-bit key), and our JSON structure can exceed this limit. We employ a standard hybrid encryption strategy:

1. Generate a unique, temporary AES-256 key strictly for *this specific packet*.
2. Encrypt the JSON payload utilizing **AES-256-GCM** (which is fast and provides authenticated encryption).
3. Encrypt the temporary AES key itself using **RSA-OAEP**.
4. Combine the results into a single byte array: `[256 bytes RSA-encrypted AES key][12 bytes IV][AES ciphertext + 16-byte GCM tag]`.

**Why AES-GCM?** It includes built-in message authentication. If an intermediate node alters a single bit in the ciphertext, the decryption process will instantly throw an exception because the GCM validation tag will fail. The server cannot be coerced into processing manipulated data.

This is fundamentally similar to how TLS operates. Reference `HybridCryptoService.java` for implementation details.

### Challenge 2: Handling Duplicate Packet Storms

Imagine three different bridge nodes holding the same packet simultaneously walk into a 4G coverage area and POST to `/api/bridge/ingest` within milliseconds of each other. Naively processing all three requests would result in the sender being debited ₹1500 instead of ₹500.

**Solution: Atomic compare-and-set operations on the ciphertext hash.**

Upon receiving a packet, the server's immediate first step is to compute `SHA-256(ciphertext)` and attempt to "claim" it:

```java
// IdempotencyService.java
Instant prev = seen.putIfAbsent(packetHash, now);
return prev == null;  // true = first claimer, false = duplicate
```

The `ConcurrentHashMap.putIfAbsent` operation is thread-safe and atomic. Even if 100 concurrent requests arrive simultaneously, exactly one thread will receive `null` (becoming the primary processor), while the others receive the existing timestamp. Only the primary thread continues to the decryption and settlement phases. The rest immediately return `DUPLICATE_DROPPED`.

**Why hash the ciphertext instead of the `packetId` or the plaintext?**
- A malicious intermediary could easily modify the `packetId`, causing two instances of the same payment to appear unique.
- Relying on the plaintext would require expensive RSA decryption for every incoming request before deduplication could occur.
- The ciphertext is cryptographically authenticated by the GCM tag, making any tampering detectable upon decryption. Two legitimate deliveries of the identical payment will have byte-for-byte identical ciphertexts (since AES is deterministic given the same key, IV, and plaintext).

In a production environment, this `ConcurrentHashMap` logic would be replaced by Redis: `SET key NX EX 86400`, distributing the lock across a cluster.

As a final defense mechanism, the `transactions.packet_hash` database column has a unique constraint. If the cache layer ever fails, the relational database will violently reject the duplicate insertion.

### Challenge 3: Mitigating Replay Attacks

An attacker who intercepted a ciphertext a month ago might attempt to replay it at an opportune moment.

**Solution: A Two-Layered Defense.**

1. **Embedded Timestamps**: The sender embeds a `signedAt` timestamp (epoch milliseconds) within the encrypted JSON payload. The backend rigidly rejects any packet older than 24 hours. The attacker cannot alter `signedAt` without invalidating the GCM cryptographic tag.
2. **Embedded Nonces**: The encrypted payload also contains a unique **nonce** (UUID). If a user legitimately transfers ₹100 to the same person twice, the differing nonces result in entirely different ciphertexts (and thus different ciphertext hashes), allowing both to process normally. However, a malicious *replay* of a previously processed packet will yield an identical ciphertext hash, triggering the idempotency filter.

Reference `BridgeIngestionService.java` to review the freshness validation logic.

---

## Project Structure

```text
NexusPay (UPI-ZERO)/
├── pom.xml                                  Maven configuration (Spring Boot 3.3, Java 17)
├── mvnw, mvnw.cmd                           Maven wrappers (no local Maven install required)
├── README.md                                This documentation file
└── src/main/
    ├── resources/
    │   ├── application.properties           Configuration for H2 DB, port 8080, and TTLs
    │   └── templates/dashboard.html         The interactive frontend demo interface
    └── java/com/demo/upimesh/
        ├── UpiMeshApplication.java          Spring Boot entry point
        │
        ├── model/                           ── Domain Layer
        │   ├── Account.java                 JPA Entity (@Version enables optimistic locking)
        │   ├── AccountRepository.java       Spring Data JPA repository
        │   ├── Transaction.java             Settled transaction ledger (unique index on packetHash)
        │   ├── TransactionRepository.java   Spring Data JPA repository
        │   ├── MeshPacket.java              Network wire format (outer fields are visible, ciphertext is opaque)
        │   └── PaymentInstruction.java      The decrypted internal payload (sender/receiver/amount/nonce/time)
        │
        ├── crypto/                          ── Cryptography Layer
        │   ├── ServerKeyHolder.java         Provisions an RSA-2048 keypair on application startup
        │   └── HybridCryptoService.java     Handles RSA-OAEP + AES-256-GCM operations and hashing
        │
        ├── service/                         ── Business Logic Layer
        │   ├── DemoService.java             Initializes accounts and simulates a sending device
        │   ├── VirtualDevice.java           Represents a single mobile phone in the mesh network
        │   ├── MeshSimulatorService.java    Manages the gossip protocol routing across virtual devices
        │   ├── IdempotencyService.java      Utilizes ConcurrentHashMap as a local equivalent to Redis SETNX
        │   ├── SettlementService.java       Executes the @Transactional debit/credit and ledger insertion
        │   └── BridgeIngestionService.java  The primary pipeline: Hash → Claim → Decrypt → Validate → Settle
        │
        ├── controller/                      ── HTTP Interface Layer
        │   ├── ApiController.java           Exposes all REST API endpoints
        │   └── DashboardController.java     Serves the primary UI dashboard
        │
        └── config/
            └── AppConfig.java               Enables @EnableScheduling for cache maintenance
```

---

## API Documentation

| HTTP Method | Endpoint | Description |
|---|---|---|
| GET | `/` | Serves the interactive Dashboard HTML |
| GET | `/api/server-key` | Returns the server's RSA public key (base64 encoded) |
| GET | `/api/accounts` | Retrieves all registered accounts and current balances |
| GET | `/api/transactions` | Fetches the 20 most recent ledger transactions |
| GET | `/api/mesh/state` | Dumps the internal state of all virtual mesh devices |
| POST | `/api/demo/send` | Simulates a sender device initiating and encrypting a new packet |
| POST | `/api/mesh/gossip` | Triggers a single round of gossip propagation across the network |
| POST | `/api/mesh/flush` | Commands all internet-connected bridges to upload their packets in parallel |
| POST | `/api/mesh/reset` | Purges the mesh state and resets the idempotency cache |
| POST | `/api/bridge/ingest` | **The core production endpoint.** Real bridge nodes submit payloads here |
| GET | `/h2-console` | Provides a web interface to inspect the in-memory database |

**H2 Console Credentials**: Use JDBC URL `jdbc:h2:mem:upimesh`, username `sa`, and leave the password blank.

### Payload Schema for `/api/bridge/ingest`

```http
POST /api/bridge/ingest
Content-Type: application/json
X-Bridge-Node-Id: phone-bridge-42
X-Hop-Count: 3

{
  "packetId": "550e8400-e29b-41d4-a716-446655440000",
  "ttl": 2,
  "createdAt": 1730000000000,
  "ciphertext": "base64-encoded-RSA-and-AES-blob"
}
```

**Expected Response:**
```json
{
  "outcome": "SETTLED",                     // Alternatively: "DUPLICATE_DROPPED" or "INVALID"
  "packetHash": "a3f8c9...",
  "reason": null,                            // Contains context if outcome is INVALID
  "transactionId": 42                        // Assigned when outcome is SETTLED
}
```

---

## Running Tests

Execute the full test suite using:
```cmd
mvnw.cmd test
```

Key test cases included:

- **`encryptDecryptRoundTrip`**: Validates the symmetry and integrity of the hybrid cryptography logic.
- **`tamperedCiphertextIsRejected`**: Deliberately mutates a byte in the generated ciphertext to confirm that `BridgeIngestionService` properly returns `INVALID` rather than crashing or accepting the payload.
- **`singlePacketDeliveredByThreeBridgesSettlesExactlyOnce`**: The critical concurrency test. It spawns three distinct threads delivering the same packet at the exact same moment. It rigorously asserts that one thread returns `SETTLED`, the other two return `DUPLICATE_DROPPED`, and the sender's balance is only decremented once.

---

## Production Considerations

While fully functional, this repository is tailored for demonstration and education. A production deployment would require the following architectural substitutions:

| Demo Implementation | Production Equivalent |
|---|---|
| H2 In-Memory Database | Scalable relational DB like PostgreSQL or MySQL with read replicas |
| `ConcurrentHashMap` for deduplication | Distributed Redis cluster utilizing `SET NX EX` |
| Ephemeral RSA keypair generated on boot | Private keys secured in an HSM (e.g., AWS KMS or HashiCorp Vault); public keys cached client-side |
| Backend simulation via `DemoService` | Native Android/iOS implementation executing the identical cryptographic flow |
| Simulated mesh network (`MeshSimulatorService`) | Real-world BLE GATT or Wi-Fi Direct peer-to-peer connections |
| Isolated local settlement ledger | Direct integration with NPCI or a banking core banking system |
| Unauthenticated `/api/bridge/ingest` | Mutual TLS (mTLS) or cryptographically signed requests from bridge nodes |
| Pre-seeded mock accounts | Verified KYC accounts, legitimate VPAs, and actual bank PIN verification |
| Exposed H2 console | Completely disabled and removed |
| Unrestricted API access | Aggressive rate limiting per bridge node and velocity checks per sender |
| Standard console logging | Structured JSON logs ingested into a SIEM, with alerts triggered by frequent `INVALID` attempts |

The core cryptography and concurrency handling within this project accurately reflect production requirements; only the surrounding infrastructure requires scaling.

---

## Inherent Limitations

To ensure a clear and honest evaluation of this system's architecture, it is important to understand the fundamental constraints of "zero-internet" routing. These are not software bugs, but inherent challenges of deferred settlement architectures:

1. **Lack of Real-Time Funds Verification**: The receiving party cannot cryptographically prove that the sender actually holds sufficient funds. When a sender's offline device displays "₹500 sent", this is essentially a digital IOU rather than a guaranteed settlement. If the sender's account is empty when the packet eventually hits the backend, the transaction will be `REJECTED`, leaving the receiver unpaid. *This is specifically why production offline payment systems (like UPI Lite) utilize pre-funded, hardware-secured wallets*—to guarantee offline availability of funds.
2. **Vulnerability to Offline Double-Spending**: A malicious user with ₹500 in their account could theoretically generate a packet for Person A in one location, move to another location, and generate a second packet for Person B. Whichever transaction reaches the backend infrastructure first will settle; the latter will bounce. This stems from the same root cause as the previous point.
3. **Physical Connectivity Challenges**: Establishing reliable device-to-device Bluetooth communication in real-world scenarios is complex. Modern mobile OS background execution limits (such as Android 8+ BLE throttling and iOS peripheral mode restrictions) mean that strangers' phones automatically forming GATT connections without active user intervention is technically demanding and battery-intensive. This demo sidesteps physical layer issues by simulating the mesh.
4. **Privacy and Metadata Considerations**: In a real-world mesh, a stranger's phone is caching and transmitting your encrypted financial data. While they cannot decrypt the payload, the metadata (packet existence, routing paths) remains visible. Production rollouts must carefully consider regulatory requirements, data minimization, and the implications of device seizures.

For academic or portfolio presentations, framing this architecture honestly as **"mesh-routed deferred settlement"** (as exemplified by **NexusPay**) rather than claiming it is a flawless "real-time offline UPI" replacement will significantly strengthen your technical pitch. The cryptographic integrity and idempotency implementations demonstrated here represent serious, production-grade engineering worth highlighting.

---

## Troubleshooting

**`java: command not found`**
Ensure JDK 17+ is installed. On Windows, you can run `winget install EclipseAdoptium.Temurin.17.JDK` or download the installer from adoptium.net.

**Port 8080 already in use**
Modify the `server.port` value within `application.properties` to an available port.

**The first `mvnw.cmd` execution appears frozen**
The initial run pulls Maven itself (~10 MB) followed by all project dependencies (~80 MB). Depending on network speed, this requires 2–3 minutes. Subsequent launches will take about 5 seconds.

**`mvnw.cmd : The term 'mvnw.cmd' is not recognized`**
In PowerShell environments, execute the script by prepending `.\`: `.\mvnw.cmd spring-boot:run`.

**Intermittent Test Failures**
The idempotency concurrency test relies on tight thread timing. If it occasionally fails, try running it a few more times. If it consistently fails on your specific machine, please report the failure output.

---

## License

This demonstration code is provided without a formal license. Feel free to use, modify, and learn from it as you see fit.
