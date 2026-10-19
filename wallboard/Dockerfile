# Small static binary on a minimal image. Lets the wallboard run without Go installed.
FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
RUN CGO_ENABLED=0 go build -o /wallboard .

FROM alpine:3.21
COPY --from=build /wallboard /wallboard
ENTRYPOINT ["/wallboard"]
