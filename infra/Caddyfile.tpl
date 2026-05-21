${duckdns_subdomain}.duckdns.org {
    reverse_proxy localhost:8080
    encode gzip
    log {
        output file /var/log/caddy/chess-backend.log
        format json
    }
}
