![modum.io Logo](https://assets.modum.io/wp-content/uploads/2017/03/modum_logo_white_space-cropped.png)

# modum.io TokenApp Monitor

This is the code for the modum token sale app backend, found under https://token.modum.io. For more information, visit https://modum.io/tokensale

## Description



The monitor application is a separate service, that watches all pay-in addresses, logs any payments made and sends an email to the user for each successful payment.

Additionally the total amount of funds raised is calculated and served on a single endpoint. This number is then used by the frontend to calculate the current bonus tier.


## API

### Get total amount raised

```
GET /

Returns:
2019013
```

## Configuration

The following configuration parameters are set via environment variables at application start:

### Required Parameters
* ETHER_FULLNODE_URL
* JDBC_URL
* MODUM_TOKENAPP_EMAIL_USERNAME
* MODUM_TOKENAPP_EMAIL_PASSWORD
* MODUM_TOKENAPP_EMAIL_HOST
* MODUM_TOKENAPP_EMAIL_PORT
* MODUM_TOKENAPP_EMAIL_BCC

### Optional Parameters
* DATASOURCE_USERNAME
* DATASOURCE_PASSWORD
* MODUM_TOKENAPP_CREATE_SCHEMA
* MODUM_TOKENAPP_ENABLE_CORS
* MODUM_TOKENAPP_BITCOIN_NETWORK
* MODUM_TOKENAPP_START_AMOUNT
* START_BLOCK_ETHER
