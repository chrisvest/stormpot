
mkdir -p data
export MSG='-Dreport.msg=## %s,%7d,%3d,%7.0f,%3d,%.6f,%s,%.6f%n'

function mark-single {
  echo "Single-threaded $1 benchmark..."
  echo '1/3... '
  mvn -Dthroughput-single "-Dpools=$1" "$MSG" | grep '##' | tee "data/$1-single-1.csv"
  echo '2/3... '
  mvn -Dthroughput-single "-Dpools=$1" "$MSG" | grep '##' | tee "data/$1-single-2.csv"
  echo '3/3... '
  mvn -Dthroughput-single "-Dpools=$1" "$MSG" | grep '##' | tee "data/$1-single-3.csv"
  echo 'done.'
}

function mark-multi {
  for n in 1 2 3 4 5 6 8 10 12 14 16
  do
    echo "Benchmarking $1 with $n threads..."
    echo '1/3... '
    mvn -Dthroughput-multi "-Dthread.count=$n" "-Dpools=$1" "$MSG" | grep '##' | tee "data/$1-t$n-1.csv"
    echo '2/3... '
    mvn -Dthroughput-multi "-Dthread.count=$n" "-Dpools=$1" "$MSG" | grep '##' | tee "data/$1-t$n-2.csv"
    echo '3/3... '
    mvn -Dthroughput-multi "-Dthread.count=$n" "-Dpools=$1" "$MSG" | grep '##' | tee "data/$1-t$n-3.csv"
    echo 'done.'
  done
}

echo START `date`

mark-single "queue"
mark-single "stack"
mark-single "generic"
mark-single "furious"

mark-multi "queue"
mark-multi "stack"
mark-multi "generic"
mark-multi "furious"

echo STOP `date`
