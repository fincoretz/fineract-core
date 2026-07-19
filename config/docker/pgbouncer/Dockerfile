FROM edoburu/pgbouncer:v1.24.1-p1

COPY pgbouncer.ini /etc/pgbouncer/pgbouncer.ini
COPY userlist.txt /etc/pgbouncer/userlist.txt

HEALTHCHECK --interval=10s --timeout=5s --retries=5 \
    CMD pg_isready -h localhost -p 5432 -U pgbouncer_auth
