# Tokenapp Monitor
Monitors the Ethereum and Bitcoin blockchains for any payments made to watched address. 
Payments are registered and a confirmation mail is sent. The total amount raised in USD<sup>1</sup> is kept in-memory and is served on a single endpoint.

<sup>1</sup>: Each payment uses the exchange rate at the time of the block that it is included in.

## Configuration
All configurations are made via environment variables.

### START_BLOCK_ETHER
The block height at which to start scanning the ethereum blockchain.
Setting this to a block height close to before any payments are expected speeds up the start-up phase.

Example: 601000

### ETHER_FULLNODE_URL (required)
The Http URL of the Ethereum Fullnode which is used for RPC calls.

Example: http://localhost:8545/

### JDBC_URL (required)
The JDBC url of the database. Make sure the corresponding driver is on the classpath.

Example: jdbc:postgresql://localhost/postgres

### DATASOURCE_USERNAME
Database username

Example: postgres
  
### DATASOURCE_PASSWORD
Database password

Example: hunter21!

### MODUM_TOKENAPP_BITCOIN_NETWORK
Which bitcoin network to monitor. Can be `mainnet`, `testnet`, or `regtest`. 
Defaults to `mainnet`.

Example: testnet

### MODUM_TOKENAPP_ENABLE_CORS
Can be `true` or `false`. When set to `true`: Adds CORS headers and allows OPTIONS calls.
Defaults to `false`.
Useful during development.

Example: true

### MODUM_TOKENAPP_CREATE_SCHEMA
Can be `true` or `false`. When set to `true`: Creates the needed database tables and triggers.
Defaults to `false`.
Useful during development.

Example: true

